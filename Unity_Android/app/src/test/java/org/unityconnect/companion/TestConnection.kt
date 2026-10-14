package org.unityconnect.companion

import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A framed peer for exercising the production coordinator, queues and dispatch. TLS has its own tests. */
internal class TestConnection(private val service: CompanionService, both: Boolean = false) : Closeable {
 val lan = Peer()
 val bluetooth = Peer()
 val attempts = java.util.concurrent.atomic.AtomicInteger()
 @Volatile var rejectBluetooth = false
 private val connection: Connection
 init {
  service.connection.stop()
  service.getSharedPreferences("settings", 0).edit().putString("host", "test.invalid")
   .putString("fingerprint", "ab".repeat(32)).putString("transport", if (both) "both" else "lan")
   .putString("bluetooth_address", "AA:BB:CC:DD:EE:FF").commit()
  connection = Connection(service, { "cd".repeat(32) }, {}, { endpoint, opening ->
   val peer = if (endpoint.bluetooth) {
    attempts.incrementAndGet()
    check(!rejectBluetooth) { "Bluetooth unavailable" }
    bluetooth
   } else lan
   peer.channel().also { opening(it) }
  })
  service.connection = connection
  connection.start()
  await { connection.ready && connection.desktopAvailable("lock") && (!both || connection.transportLabel == "Bluetooth + Wi-Fi / LAN") }
 }
 override fun close() = connection.stop()
 fun await(test: () -> Boolean) {
  val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
  while (!test()) { check(System.nanoTime() < end) { "Timed out" }; Thread.sleep(10) }
 }
 internal class Peer {
  val messages = LinkedBlockingQueue<JSONObject>()
  private var incoming = LinkedBlockingQueue<ByteArray>()
  @Volatile var closed = false
  private var secure: SecureChannel? = null
  @Synchronized fun channel(): SecureChannel {
   incoming = LinkedBlockingQueue(); closed = false
   val frames = incoming
   val input = object : InputStream() {
    var bytes = byteArrayOf(); var index = 0
    override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
    override fun read(b: ByteArray, offset: Int, count: Int): Int {
     if (count == 0) return 0
     if (index == bytes.size) { bytes = frames.take(); index = 0; if (bytes.isEmpty()) return -1 }
     val n = minOf(count, bytes.size - index); bytes.copyInto(b, offset, index, index + n); index += n; return n
    }
   }
   val output = object : ByteArrayOutputStream() {
    @Synchronized override fun flush() {
     if (size() == 0) return
     val message = Protocol.read(DataInputStream(ByteArrayInputStream(toByteArray()))); reset()
     when (message.getString("type")) {
      "hello" -> send(JSONObject().put("type", "challenge").put("nonce", "ef".repeat(32)))
      "proof" -> {
       send(JSONObject().put("type", "ready"))
       send(JSONObject().put("type", "capabilities").put("capabilities", JSONArray().put(JSONObject().put("id", "lock").put("status", "available"))))
      }
      else -> messages.offer(message)
     }
    }
   }
   return SecureChannel(input, output, Closeable { closed = true; frames.offer(byteArrayOf()) }, {}).also { secure = it }
  }
  fun send(message: JSONObject) {
   val bytes = ByteArrayOutputStream(); Protocol.write(DataOutputStream(bytes), message); incoming.put(bytes.toByteArray())
  }
  fun disconnect() { secure?.close() }
  fun next(predicate: (JSONObject) -> Boolean): JSONObject {
   val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
   while (System.nanoTime() < end) {
    val item = messages.poll(50, TimeUnit.MILLISECONDS) ?: continue
    if (predicate(item)) return item
   }
   error("Expected message did not arrive")
  }
 }
}
