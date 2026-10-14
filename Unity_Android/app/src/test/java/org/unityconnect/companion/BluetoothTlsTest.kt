package org.unityconnect.companion

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Real TLS 1.3, including record fragmentation and concurrent duplex file traffic. */
class BluetoothTlsTest {
 private fun identity(): Pair<SSLContext, String> {
  val store = KeyStore.getInstance("PKCS12")
  javaClass.getResourceAsStream("/bluetooth-test.p12").use { store.load(it, "unity-test-only".toCharArray()) }
  val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "unity-test-only".toCharArray()) }
  val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
  val pin = MessageDigest.getInstance("SHA-256").digest(store.getCertificate("unity-test").encoded).joinToString("") { "%02x".format(it) }
  return context to pin
 }
 @Test fun fragmentedRecordsCarryConcurrentFilesAndControlInBothDirections() {
  val (serverContext, pin) = identity()
  val pool = Executors.newFixedThreadPool(3)
  val data = ByteArray(256 * 1024) { (it % 251).toByte() }
  val reverse = data.reversedArray()
  (serverContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket).use { server ->
   server.enabledProtocols = arrayOf("TLSv1.3")
   val peer = pool.submit {
    (server.accept() as SSLSocket).use { tls ->
     tls.soTimeout = 10000; tls.startHandshake()
     val writer = pool.submit { tls.outputStream.write(reverse); tls.outputStream.flush() }
     val received = ByteArray(data.size); DataInputStream(tls.inputStream).readFully(received)
     assertArrayEquals(data, received); writer.get(10, TimeUnit.SECONDS)
     assertEquals(42, DataInputStream(tls.inputStream).readInt())
     DataOutputStream(tls.outputStream).writeInt(84)
    }
   }
   val raw = Socket("127.0.0.1", server.localPort).apply { soTimeout = 10000 }
   val fragmented = object : FilterInputStream(raw.inputStream) {
    override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(length, 7))
   }
   val engine = PinnedTls.context(pin).createSSLEngine().apply { useClientMode = true; enabledProtocols = arrayOf("TLSv1.3") }
   StreamTls(engine, fragmented, raw.outputStream, raw).use { tls ->
    tls.handshake()
    val writer = pool.submit { tls.outputStream.write(data); DataOutputStream(tls.outputStream).writeInt(42) }
    val received = ByteArray(reverse.size); DataInputStream(tls.inputStream).readFully(received)
    assertArrayEquals(reverse, received); writer.get(10, TimeUnit.SECONDS)
    assertEquals(84, DataInputStream(tls.inputStream).readInt())
    assertEquals(-1, tls.inputStream.read())
   }
   peer.get(10, TimeUnit.SECONDS)
  }
  pool.shutdownNow()
 }
 @Test fun wrongDesktopPinIsRejectedBeforeApplicationData() {
  val (serverContext, _) = identity()
  val pool = Executors.newSingleThreadExecutor()
  (serverContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket).use { server ->
   server.enabledProtocols = arrayOf("TLSv1.3")
   val peer = pool.submit<Boolean> {
    (server.accept() as SSLSocket).use { tls ->
     tls.soTimeout = 3000
     try { tls.startHandshake(); tls.inputStream.read() < 0 } catch (_: Exception) { true }
    }
   }
   val raw = Socket("127.0.0.1", server.localPort).apply { soTimeout = 3000 }
   val engine = PinnedTls.context("00".repeat(32)).createSSLEngine().apply { useClientMode = true; enabledProtocols = arrayOf("TLSv1.3") }
   StreamTls(engine, raw.inputStream, raw.outputStream, raw).use { tls -> assertThrows(Exception::class.java) { tls.handshake() } }
   assertTrue(peer.get(5, TimeUnit.SECONDS))
  }
  pool.shutdownNow()
 }
 @Test fun truncatedEncryptedRecordCannotBecomeAnApplicationMessage() {
  val engine = SSLContext.getDefault().createSSLEngine().apply { useClientMode = true; enabledProtocols = arrayOf("TLSv1.3") }
  val source = java.io.ByteArrayInputStream(byteArrayOf(22, 3, 3, 0, 12, 1, 2))
  StreamTls(engine, source, java.io.ByteArrayOutputStream(), source).use { tls -> assertThrows(java.io.EOFException::class.java) { tls.handshake() } }
 }
}
