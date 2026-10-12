package org.unityconnect.companion

import android.os.Build
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class Connection(private val service: CompanionService) {
 private val scheduler = ScheduledThreadPoolExecutor(1)
 private val outgoing = ArrayBlockingQueue<JSONObject>(32)
 @Volatile private var stopped = false
 @Volatile private var socket: Socket? = null
 @Volatile var ready = false
 private var attempt = 0
 fun start() { scheduler.execute { connect() } }
 fun stop() { stopped = true; ready = false; socket?.close(); scheduler.shutdownNow(); outgoing.clear() }
 fun send(message: JSONObject, blocking: Boolean = false): Boolean {
  if (!ready) return false
  return if (blocking) outgoing.offer(message, 10, TimeUnit.SECONDS) else outgoing.offer(message)
 }
 private fun connect() {
  if (stopped) return
  var writer: Thread? = null
  try {
   val prefs = service.getSharedPreferences("settings", 0)
   val host = prefs.getString("host", "")!!
   val fingerprint = prefs.getString("fingerprint", "")!!.lowercase().replace(":", "").replace(" ", "")
   require(host.isNotBlank() && fingerprint.length == 64 && Protocol.unhex(fingerprint).size == 32) { "Enter desktop address and verified fingerprint" }
   val trust = object : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) { error("Not a server") }
    override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {
     require(chain.isNotEmpty())
     chain[0].checkValidity()
     check(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(chain[0].encoded), Protocol.unhex(fingerprint))) { "Desktop TLS identity changed. Verify and pair again." }
    }
   }
   val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
   val raw = Socket(); socket = raw
   raw.connect(InetSocketAddress(host, prefs.getInt("port", 17161)), 10000)
   val tls = context.socketFactory.createSocket(raw, host, raw.port, true) as SSLSocket
   socket = tls; tls.enabledProtocols = arrayOf("TLSv1.3"); tls.soTimeout = 100000; tls.startHandshake()
   val input = DataInputStream(tls.inputStream); val output = DataOutputStream(tls.outputStream)
   Protocol.write(output, JSONObject().put("type", "hello").put("version", 1).put("device_id", service.credentials.id)
    .put("name", (Build.MODEL ?: "Android").take(128)).put("capabilities", service.features.capabilities()))
   var reply = Protocol.read(input)
   if (reply.getString("type") == "pair_pending") {
    service.status("Approve this phone in Unity on your computer.", ConnectionPhase.PAIRING)
    reply = Protocol.read(input)
    require(reply.getString("type") == "pair_result")
    service.credentials.storeSecret(reply.getString("secret"))
    service.status("Paired. Establishing your trusted connection…", ConnectionPhase.RECONNECTING)
    attempt = 0
    return
   }
   require(reply.getString("type") == "challenge")
   val secret = service.credentials.readSecret() ?: error("Desktop expects a paired identity. Remove this phone on desktop and pair again.")
   val nonce = reply.getString("nonce"); require(nonce.length == 64)
   Protocol.write(output, JSONObject().put("type", "proof").put("mac", Protocol.proof(secret, service.credentials.id, nonce)))
   require(Protocol.read(input).getString("type") == "ready")
   ready = true; attempt = 0; outgoing.clear()
   writer = Thread({
    try { while (!stopped && !tls.isClosed) Protocol.write(output, outgoing.take()) }
    catch (_: Exception) { runCatching { tls.close() } }
   }, "unity-writer").apply { start() }
   service.status("Your computer is ready to share over the local network.", ConnectionPhase.CONNECTED)
   service.features.publish()
   while (!stopped) {
    val message = Protocol.read(input)
    when (message.getString("type")) {
     "ping" -> send(JSONObject().put("type", "pong"))
     "pong" -> Unit
     "command" -> service.dispatch(message)
     else -> error("Unexpected protocol message")
    }
   }
  } catch (e: Exception) { ready = false; if (!stopped) service.status((e.message ?: "Connection lost").take(180), ConnectionPhase.RECONNECTING) }
  finally {
   ready = false; runCatching { socket?.close() }; writer?.interrupt(); service.features.disconnected()
   service.uiChanged()
   if (!stopped) {
    val delay = (1L shl attempt.coerceAtMost(6)) * 1000 + (0..500).random()
    attempt++
    scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
   }
  }
 }
}
