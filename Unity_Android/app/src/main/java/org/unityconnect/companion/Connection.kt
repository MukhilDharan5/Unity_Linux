package org.unityconnect.companion

import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class Connection internal constructor(private val service: CompanionService,
 private val readSecret: () -> String? = { service.credentials.readSecret() },
 private val storeSecret: (String) -> Unit = { service.credentials.storeSecret(it) },
 private val openControl: (TlsEndpoint, (Closeable) -> Unit) -> SecureChannel = { endpoint, opening -> endpoint.open(StreamKind.CONTROL, 100000, opening) }) {
 private val scheduler = ScheduledThreadPoolExecutor(2).apply { removeOnCancelPolicy = true }
 private val routes = ConnectionRoutes<Channel>()
 private val lifecycle = Any()
 private val pairing = ReentrantLock()
 private val opening = ConcurrentHashMap<LinkKind, Closeable>()
 private val commands = ConcurrentHashMap<String, Channel>()
 private val streams = ConcurrentHashMap<String, Channel>()
 private data class Pending(val channel: Channel, val callback: (Boolean, String) -> Unit)
 private val replies = ConcurrentHashMap<String, Pending>()
 private val main = Handler(Looper.getMainLooper())
 @Volatile private var stopped = false
 @Volatile var ready = false
 private val configured = linkedSetOf<LinkKind>()
 val transportLabel get() = when (routes.kinds()) {
  setOf(LinkKind.BLUETOOTH, LinkKind.LAN) -> "Bluetooth + Wi-Fi / LAN"
  setOf(LinkKind.BLUETOOTH) -> "Bluetooth"
  else -> "Wi-Fi / LAN"
 }

 private inner class Channel(val kind: LinkKind, val endpoint: TlsEndpoint, val tls: SecureChannel) {
  val outgoing = ArrayBlockingQueue<JSONObject>(32)
  @Volatile var capabilities = JSONArray()
  @Volatile var open = true
  var writer: Thread? = null
  fun send(message: JSONObject, blocking: Boolean = false): Boolean {
   if (!open || stopped || !routes.contains(this)) return false
   val accepted = if (blocking) outgoing.offer(message, 10, TimeUnit.SECONDS) else outgoing.offer(message)
   return accepted && open && !stopped && routes.contains(this)
  }
  fun close() { open = false; runCatching { tls.close() }; writer?.interrupt(); outgoing.clear() }
 }

 internal fun streamEndpoint(requestId: String, feature: String): TlsEndpoint {
  require(feature == "audio" || feature == "screen")
  val channel = commands[requestId] ?: error("The connection for this stream disconnected")
  streams[feature] = channel
  if (!channel.open || !routes.contains(channel)) {
   streams.remove(feature, channel); error("The connection for this stream disconnected")
  }
  return channel.endpoint
 }
 /** Hold this exact channel until end/abort. A reconnect must never continue a partial file. */
 internal fun fileSender(): (JSONObject) -> Unit {
  val channel = routes.select("files") ?: error("Connect first")
  return { data -> check(channel.send(JSONObject().put("type", "event").put("feature", "files").put("data", data), true)) {
   "File connection lost or send queue full; retry the transfer"
  } }
 }
 fun desktopAvailable(feature: String): Boolean = routes.values().any { channel ->
  (0 until channel.capabilities.length()).any { channel.capabilities.optJSONObject(it)?.let { c ->
   c.optString("id") == feature && c.optString("status") == "available"
  } == true }
 }
 fun request(feature: String, action: String, data: JSONObject = JSONObject(), reply: (Boolean, String) -> Unit) {
  check(ready) { "Connect first" }
  check(desktopAvailable(feature)) { "Enable this feature on your computer and update both apps" }
  require(feature in listOf("lock", "screen"))
  val channel = routes.select(feature) ?: error("Connect first")
  val id = UUID.randomUUID().toString().replace("-", "")
  synchronized(replies) { check(replies.size < 8) { "Too many pending requests" }; replies[id] = Pending(channel, reply) }
  if (!channel.send(JSONObject().put("type", "request").put("id", id).put("feature", feature).put("action", action).put("data", data))) {
   replies.remove(id); error("Connection unavailable or send queue full")
  }
  main.postDelayed({ replies.remove(id)?.callback?.invoke(false, "Computer request timed out") }, 10000)
 }
 private fun failReplies(channel: Channel? = null) {
  replies.entries.toList().forEach { (id, pending) ->
   if ((channel == null || pending.channel === channel) && replies.remove(id, pending)) {
    main.post { pending.callback(false, "The connection for this request disconnected") }
   }
  }
 }
 fun start() {
  val prefs = service.getSharedPreferences("settings", 0)
  val mode = prefs.getString("transport", "both")
  if (mode != "bluetooth" && prefs.getString("host", "").orEmpty().isNotBlank()) configured.add(LinkKind.LAN)
  if (mode != "lan" && prefs.getString("bluetooth_address", "").orEmpty().isNotBlank()) configured.add(LinkKind.BLUETOOTH)
  if (configured.isEmpty()) { service.status("Add your computer in Settings", ConnectionPhase.RECONNECTING); return }
  configured.forEach { kind -> scheduler.execute { connect(kind, 0) } }
 }
 fun stop() {
  synchronized(lifecycle) {
   stopped = true; ready = false
   opening.values.forEach { runCatching { it.close() } }; opening.clear()
   routes.values().forEach { routes.remove(it.kind, it); it.close() }
   failReplies(); commands.clear(); streams.clear(); service.features.disconnected()
  }
  scheduler.shutdownNow()
 }
 fun send(message: JSONObject, blocking: Boolean = false): Boolean {
  if (stopped) return false
  if (message.optString("type") == "capabilities") {
   val channels = routes.values()
   return channels.isNotEmpty() && channels.map { it.send(message, blocking) }.all { it }
  }
  if (message.optString("type") == "result") {
   // Reply on the command's original link, even if the preferred link changed.
   val id = message.optString("id")
   val channel = commands.remove(id) ?: return false
   return channel.send(message, blocking)
  }
  return routes.select(message.optString("feature"))?.send(message, blocking) ?: false
 }
 private fun connectedStatus() {
  val waiting = configured - routes.kinds()
  val suffix = if (waiting.isEmpty()) "" else " Reconnecting ${if (LinkKind.BLUETOOTH in waiting) "Bluetooth" else "Wi-Fi / LAN"}…"
  service.status("Connected over $transportLabel.$suffix", ConnectionPhase.CONNECTED)
 }
 private fun connect(kind: LinkKind, attempt: Int) {
  if (stopped) return
  var channel: Channel? = null
  var pairingHeld = false
  var nextAttempt = attempt + 1
  var failure = "Connection lost"
  var waitingForPair = false
  try {
   // Only one first-time pairing prompt/secret exchange, then authenticate both links.
   if (readSecret() == null) {
    pairingHeld = pairing.tryLock()
    if (!pairingHeld) { waitingForPair = true; nextAttempt = 0; return }
   }
   val prefs = service.getSharedPreferences("settings", 0)
   val fingerprint = prefs.getString("fingerprint", "").orEmpty().lowercase().replace(":", "").replace(" ", "")
   require(fingerprint.length == 64 && Protocol.unhex(fingerprint).size == 32) { "Enter the verified computer fingerprint" }
   val destination = TlsEndpoint(prefs.getString("host", "").orEmpty(), prefs.getInt("port", 17161), PinnedTls.context(fingerprint),
    if (kind == LinkKind.BLUETOOTH) prefs.getString("bluetooth_address", "").orEmpty() else "", service)
   val tls = openControl(destination) { socket ->
    opening[kind] = socket; if (stopped) { socket.close(); error("Connection cancelled") }
   }
   opening[kind] = tls
   val input = DataInputStream(tls.inputStream); val output = DataOutputStream(tls.outputStream)
   Protocol.write(output, JSONObject().put("type", "hello").put("version", 1).put("device_id", service.credentials.id)
    .put("name", (Build.MODEL ?: "Android").take(128)).put("capabilities", service.features.capabilities()))
   var response = Protocol.read(input)
   if (response.getString("type") == "pair_pending") {
    service.status("Approve this phone in Unity on your computer.", ConnectionPhase.PAIRING)
    response = Protocol.read(input)
    require(response.getString("type") == "pair_result")
    storeSecret(response.getString("secret"))
    failure = "Paired. Establishing both trusted connections…"; nextAttempt = 0
    return
   }
   require(response.getString("type") == "challenge")
   val secret = readSecret() ?: error("Remove this phone on desktop and pair again")
   val nonce = response.getString("nonce"); require(nonce.length == 64)
   Protocol.write(output, JSONObject().put("type", "proof").put("mac", Protocol.proof(secret, service.credentials.id, nonce)))
   require(Protocol.read(input).getString("type") == "ready")
   if (pairingHeld) { pairing.unlock(); pairingHeld = false }
   val live = Channel(kind, destination, tls); channel = live
   synchronized(lifecycle) {
    check(!stopped) { "Connection cancelled" }
    routes.put(kind, live); ready = true; nextAttempt = 0
    live.writer = Thread({
     try { while (live.open && !stopped) Protocol.write(output, live.outgoing.take()) }
     catch (_: Exception) { runCatching { tls.close() } }
    }, "unity-writer-${kind.name.lowercase()}").apply { start() }
    connectedStatus(); service.features.publish()
   }
   while (!stopped) {
    val message = Protocol.read(input)
    when (message.getString("type")) {
     "ping" -> check(live.send(JSONObject().put("type", "pong"))) { "Heartbeat queue full" }
     "pong" -> Unit
     "command" -> {
      val id = message.getString("id"); require(Protocol.validId(id))
      check(commands.size < 256 || commands.containsKey(id)) { "Too many pending commands" }
      val previous = commands.putIfAbsent(id, live)
      require(previous == null || previous === live) { "Command changed transport" }
      val feature = message.optString("feature")
      service.dispatch(message)
      if (feature == "files" && message.optString("action") == "abort") commands.remove(id, live)
     }
     "capabilities" -> {
      val caps = message.getJSONArray("capabilities"); require(caps.length() <= 32)
      live.capabilities = caps; service.uiChanged()
     }
     "result" -> {
      val id = message.getString("id"); require(Protocol.validId(id))
      val pending = replies[id]
      if (pending != null && pending.channel === live && replies.remove(id, pending)) {
       main.post { pending.callback(message.getBoolean("ok"), message.optString("error").take(512)) }
      }
     }
     else -> error("Unexpected protocol message")
    }
   }
  } catch (e: Exception) { failure = (e.message ?: "Connection lost").take(180) }
  finally {
   if (pairingHeld) pairing.unlock()
   synchronized(lifecycle) {
    channel?.let { lost ->
     routes.remove(kind, lost); lost.close(); failReplies(lost)
     commands.entries.toList().forEach { (id, origin) -> if (origin === lost && commands.remove(id, origin)) service.features.files.abort(id) }
     streams.entries.toList().forEach { (feature, origin) -> if (origin === lost && streams.remove(feature, origin)) {
      if (feature == "audio") service.features.audio.stop() else service.features.screen.stop()
     } }
    }
    runCatching { opening.remove(kind)?.close() }
    ready = routes.values().isNotEmpty()
    if (!stopped && !waitingForPair) {
     if (ready) connectedStatus() else {
      if (channel != null) service.features.disconnected()
      service.status(failure, ConnectionPhase.RECONNECTING)
     }
     service.uiChanged()
    }
   }
   if (!stopped && !scheduler.isShutdown) {
    val delay = (1L shl nextAttempt.coerceAtMost(6)) * 1000 + (0..500).random()
    runCatching { scheduler.schedule({ connect(kind, nextAttempt) }, delay, TimeUnit.MILLISECONDS) }
   }
  }
 }
}
