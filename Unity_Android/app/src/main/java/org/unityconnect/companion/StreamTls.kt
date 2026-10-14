package org.unityconnect.companion

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/** TLS records over a byte stream (RFCOMM has no TCP Socket for SSLSocket).
 * One reader and one writer may run concurrently. Buffer progress survives record fragmentation.
 * The supplied engine verifies the desktop pin before any Unity messages are exchanged.
 */
internal class StreamTls(private val engine: SSLEngine, private val source: InputStream,
 private val sink: OutputStream, private val transport: Closeable) : Closeable {
 private val readLock = Any()
 private val writeLock = Any()
 private val engineLock = Any()
 private val encrypted = ByteBuffer.allocate(65536) // write mode between unwraps
 private val encoded = ByteBuffer.allocate(65536)
 private val decoded = ByteBuffer.allocate(65536).apply { limit(0) }
 @Volatile private var closed = false
 private val empty = ByteBuffer.allocate(0)
 private fun tasks() = synchronized(engineLock) {
  while (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
   (engine.delegatedTask ?: throw SSLException("TLS task missing")).run()
  }
 }
 private fun receiveMore() {
  check(!closed) { "Bluetooth stream closed" }
  if (!encrypted.hasRemaining()) throw SSLException("TLS record exceeds buffer limit")
  val count = source.read(encrypted.array(), encrypted.position(), encrypted.remaining())
  if (count < 0) throw EOFException("Bluetooth TLS connection closed")
  if (count > 0) encrypted.position(encrypted.position() + count)
 }
 private fun unwrap(): SSLEngineResult {
  encrypted.flip()
  val result = try { synchronized(engineLock) { engine.unwrap(encrypted, decoded) } }
   finally { encrypted.compact() }
  tasks()
  if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) throw SSLException("TLS plaintext exceeds buffer limit")
  return result
 }
 private fun wrap(bytes: ByteBuffer): SSLEngineResult = synchronized(writeLock) {
  val packet = encoded.apply { clear() }
  val result = synchronized(engineLock) { engine.wrap(bytes, packet) }
  tasks()
  if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) throw SSLException("TLS record exceeds buffer limit")
  if (packet.position() > 0) { sink.write(packet.array(), 0, packet.position()); sink.flush() }
  result
 }
 fun handshake() {
  engine.beginHandshake()
  decoded.clear()
  while (true) {
   when (engine.handshakeStatus) {
    SSLEngineResult.HandshakeStatus.NEED_TASK -> tasks()
    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
     val result = wrap(empty)
     if (result.status == SSLEngineResult.Status.CLOSED) throw SSLException("TLS handshake rejected")
    }
    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
     val result = unwrap()
     if (result.status == SSLEngineResult.Status.CLOSED) throw SSLException("TLS handshake rejected")
     if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) receiveMore()
    }
    SSLEngineResult.HandshakeStatus.FINISHED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> { decoded.flip(); return }
    else -> throw SSLException("Unsupported TLS handshake state")
   }
  }
 }
 val inputStream: InputStream = object : InputStream() {
  override fun read(): Int { val byte = ByteArray(1); return if (read(byte) < 0) -1 else byte[0].toInt() and 255 }
  override fun read(bytes: ByteArray, offset: Int, length: Int): Int = synchronized(readLock) {
   require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
   if (length == 0) return@synchronized 0
   while (!decoded.hasRemaining()) {
    check(!closed) { "Bluetooth stream closed" }
    decoded.clear()
    val result = unwrap()
    decoded.flip()
    if (result.status == SSLEngineResult.Status.CLOSED) return@synchronized -1
    if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) wrap(empty)
    if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) receiveMore()
   }
   val count = minOf(length, decoded.remaining()); decoded.get(bytes, offset, count); count
  }
  override fun close() = this@StreamTls.close()
 }
 val outputStream: OutputStream = object : OutputStream() {
  override fun write(byte: Int) = write(byteArrayOf(byte.toByte()))
  override fun write(bytes: ByteArray, offset: Int, length: Int) = synchronized(writeLock) {
   require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
   check(!closed) { "Bluetooth stream closed" }
   val data = ByteBuffer.wrap(bytes, offset, length)
   while (data.hasRemaining()) {
    val result = wrap(data)
    if (result.status == SSLEngineResult.Status.CLOSED) throw EOFException("Bluetooth TLS stream closed")
    if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) throw SSLException("TLS write made no progress")
   }
  }
  override fun flush() = synchronized(writeLock) { sink.flush() }
  override fun close() = this@StreamTls.close()
 }
 override fun close() { closed = true; transport.close() }
}
