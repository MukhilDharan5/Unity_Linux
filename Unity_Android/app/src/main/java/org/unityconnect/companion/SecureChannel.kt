package org.unityconnect.companion

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.Network
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

internal enum class StreamKind(val uuid: String) {
 CONTROL("ab049870-6fb6-4d30-9b3c-190770271001"),
 AUDIO("ab049870-6fb6-4d30-9b3c-190770271002"),
 SCREEN("ab049870-6fb6-4d30-9b3c-190770271003")
}
internal object BluetoothSupport {
 const val PERMISSION_REQUEST = 21
 fun allowed(context: Context) = Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
 fun adapter(context: Context): BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
 fun validAddress(address: String) = BluetoothAdapter.checkBluetoothAddress(address.uppercase(java.util.Locale.ROOT))
}
internal class SecureChannel(val inputStream: InputStream, val outputStream: OutputStream,
 private val transport: Closeable, private val timeout: (Int) -> Unit) : Closeable {
 @Volatile var isClosed = false
  private set
 var soTimeout: Int = 0
  set(value) { require(value > 0); field = value; timeout(value) }
 override fun close() { isClosed = true; transport.close() }
}
internal data class TlsEndpoint(val host: String, val port: Int, val context: SSLContext,
 val bluetoothAddress: String = "", val android: Context? = null,
 val network: Network? = null, val connectTimeoutMs: Int = 10000, val localAddress: java.net.InetAddress? = null) {
 val bluetooth get() = bluetoothAddress.isNotEmpty()
 fun open(kind: StreamKind, readTimeout: Int, onOpening: (Closeable) -> Unit): SecureChannel {
  if (!bluetooth) {
   val raw = Socket(); onOpening(raw)
   try {
    raw.tcpNoDelay = true; raw.keepAlive = true; raw.sendBufferSize = if (kind == StreamKind.AUDIO) 4096 else 65536
    network?.bindSocket(raw)
    if (network==null && localAddress!=null) raw.bind(InetSocketAddress(localAddress,0))
    val address = network?.getAllByName(host)?.firstOrNull()?.let { InetSocketAddress(it,port) } ?: InetSocketAddress(host,port)
    raw.connect(address, connectTimeoutMs)
    val tls = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket; onOpening(tls)
    tls.enabledProtocols = arrayOf("TLSv1.3"); tls.soTimeout = maxOf(4000, connectTimeoutMs); tls.startHandshake(); tls.soTimeout = readTimeout
    return SecureChannel(tls.inputStream, tls.outputStream, tls) { tls.soTimeout = it }
   } catch (e: Exception) { raw.close(); throw e }
  }
  val app = checkNotNull(android)
  check(BluetoothSupport.allowed(app)) { "Allow Nearby devices in Unity’s Android permissions" }
  val adapter = BluetoothSupport.adapter(app) ?: error("This phone has no Bluetooth adapter")
  check(adapter.isEnabled) { "Turn on Bluetooth on your phone" }
  require(BluetoothSupport.validAddress(bluetoothAddress)) { "Choose your paired computer in Bluetooth settings" }
  val raw = adapter.getRemoteDevice(bluetoothAddress).createRfcommSocketToServiceRecord(UUID.fromString(kind.uuid))
  onOpening(raw)
  val deadline = timers.schedule({ runCatching { raw.close() } }, 20000, TimeUnit.MILLISECONDS)
  try {
   raw.connect()
   val engine = context.createSSLEngine().apply { useClientMode = true; enabledProtocols = arrayOf("TLSv1.3") }
   val timeoutMs = java.util.concurrent.atomic.AtomicInteger(readTimeout)
   val input = object : InputStream() {
    override fun read(): Int { val b = ByteArray(1); return if (read(b) < 0) -1 else b[0].toInt() and 255 }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
     if (length == 0) return 0
     val watch = timers.schedule({ runCatching { raw.close() } }, timeoutMs.get().toLong(), TimeUnit.MILLISECONDS)
     return try { raw.inputStream.read(bytes, offset, length) } finally { watch.cancel(false) }
    }
   }
   val output = object : OutputStream() {
    override fun write(value: Int) = write(byteArrayOf(value.toByte()))
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
     if (length == 0) return
     val watch = timers.schedule({ runCatching { raw.close() } }, 10000, TimeUnit.MILLISECONDS)
     try { raw.outputStream.write(bytes, offset, length) } finally { watch.cancel(false) }
    }
    override fun flush() = raw.outputStream.flush()
   }
   val tls = StreamTls(engine, input, output, raw); tls.handshake()
   return SecureChannel(tls.inputStream, tls.outputStream, tls) { timeoutMs.set(it) }
  } catch (e: Exception) { raw.close(); throw e }
  finally { deadline.cancel(false) }
 }
 companion object {
  private val timers = ScheduledThreadPoolExecutor(1) { work -> Thread(work, "unity-bluetooth-timeout").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
 }
}
