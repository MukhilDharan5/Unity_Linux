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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class Connection internal constructor(private val service: CompanionService,
 private val readSecret: () -> String? = { service.credentials.readSecret() },
 private val storeSecret: (String) -> Unit = { service.credentials.storeSecret(it) },
 private val openControl: (TlsEndpoint, (Closeable) -> Unit) -> SecureChannel = { endpoint, opening -> endpoint.open(StreamKind.CONTROL, 100000, opening) }) {
 private val scheduler = ScheduledThreadPoolExecutor(2).apply { removeOnCancelPolicy = true }
 private val routes = ConnectionRoutes<Channel>()
 private val networkPaths = NetworkPaths()
 @Volatile private var openingLanEndpoint: TlsEndpoint? = null
 @Volatile private var lanRouteRevision = 0L
 @Volatile var lanError = ""
  private set
 val lanEndpointLabel: String? get() = routes.values().firstOrNull { it.kind==LinkKind.LAN }?.endpoint?.let { "${it.host}:${it.port}" }
 internal val connectedBluetoothAddress: String? get() = routes.values().firstOrNull { it.kind == LinkKind.BLUETOOTH }?.endpoint?.bluetoothAddress
 private val lifecycle = Any()
 private val pairing = ReentrantLock()
 private val opening = ConcurrentHashMap<LinkKind, Closeable>()
 private val commands = ConcurrentHashMap<String, Channel>()
 private data class MediaCommand(val fingerprint: String,var response: JSONObject?=null)
 private val mediaCommands=linkedMapOf<String,MediaCommand>()
 private val streams = ConcurrentHashMap<String, Channel>()
 private data class Pending(val channel: Channel, val callback: (Boolean, String) -> Unit, val feature: String)
 private val replies = ConcurrentHashMap<String, Pending>()
 private val main = Handler(Looper.getMainLooper())
 @Volatile private var stopped = false
 private var started = false
 private val running = mutableSetOf<LinkKind>()
 private val scheduled = mutableMapOf<LinkKind, ScheduledFuture<*>>()
 private val revisions = mutableMapOf<LinkKind, Long>()
 private val retryImmediately = mutableSetOf<LinkKind>()
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
 internal fun fileSender(requestId: String? = null): (JSONObject) -> Unit {
  val channel = if (requestId == null) routes.select("files") ?: error("Connect first")
   else commands[requestId] ?: error("The original gallery connection disconnected")
  return { data -> check(channel.send(JSONObject().put("type", "event").put("feature", "files").put("data", data), true)) {
   "File connection lost or send queue full; retry the transfer"
  } }
 }
 fun desktopAvailable(feature: String): Boolean = routes.values().any { channel ->
  (0 until channel.capabilities.length()).any { channel.capabilities.optJSONObject(it)?.let { c ->
   c.optString("id") == feature && c.optString("status") == "available"
  } == true }
 }
 internal fun artworkRoute(): Any? = routes.select("files")
 fun request(feature: String, action: String, data: JSONObject = JSONObject(), reply: (Boolean, String) -> Unit) {
  check(ready) { "Connect first" }
  check(desktopAvailable(feature)) { "Enable this feature on your computer and update both apps" }
  require(feature in listOf("lock", "screen", "desktop_media", "sound", "audio"))
  val channel = routes.select(feature) ?: error("Connect first")
  val id = UUID.randomUUID().toString().replace("-", "")
  synchronized(replies) { check(replies.size < 8) { "Too many pending requests" }; replies[id] = Pending(channel, reply, feature) }
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
  synchronized(lifecycle) {
  if (started || stopped) return
  started = true
  val prefs = service.getSharedPreferences("settings", 0)
  val mode = prefs.getString("transport", "both")
  if (mode != "bluetooth" && prefs.getString("host", "").orEmpty().isNotBlank()) configured.add(LinkKind.LAN)
  if (mode != "lan" && prefs.getString("bluetooth_address", "").orEmpty().isNotBlank()) configured.add(LinkKind.BLUETOOTH)
  if (mode=="both" && LinkKind.BLUETOOTH in configured) configured.add(LinkKind.LAN)
  if (configured.isEmpty()) { service.status("Add your computer in Settings", ConnectionPhase.RECONNECTING); return }
  configured.forEach { kind -> schedule(kind, 0, 0) }
  }
 }
 private fun schedule(kind: LinkKind, attempt: Int, delay: Long) {
  // Called under lifecycle; at most one scheduled/running connector per radio.
  if (stopped || scheduler.isShutdown) return
  scheduled.remove(kind)?.cancel(false)
  val revision = (revisions[kind] ?: 0L) + 1
  revisions[kind] = revision
  scheduled[kind] = scheduler.schedule({
   val shouldConnect = synchronized(lifecycle) {
    if (revisions[kind] != revision) false else {
     scheduled.remove(kind)
     !stopped && running.add(kind)
    }
   }
   if (shouldConnect) connect(kind, attempt)
  }, delay, TimeUnit.MILLISECONDS)
 }
 internal fun retry(kind: LinkKind, invalidate: Boolean = false) {
  synchronized(lifecycle) {
   if (stopped || kind !in configured) return
   if (!invalidate && routes.kinds().contains(kind)) return
   if (invalidate && kind==LinkKind.LAN) lanRouteRevision++
   if (kind in running) {
    retryImmediately.add(kind)
    if (invalidate) {
     routes.values().firstOrNull { it.kind == kind }?.close()
     runCatching { opening[kind]?.close() }
    }
   } else schedule(kind, 0, 0)
  }
 }
 internal fun networkChanged(network: android.net.Network? = null, lost: Boolean = false) {
  val live=routes.values().firstOrNull { it.kind==LinkKind.LAN }
  val endpoint=live?.endpoint ?: openingLanEndpoint
  val route=endpoint?.let { LanNetwork.route(service,it.host) }
  val changed=endpoint!=null && (route?.network!=endpoint.network || route?.localAddress!=endpoint.localAddress)
  retry(LinkKind.LAN, invalidate=changed || lost && (endpoint==null || endpoint.network==network))
 }
 fun stop() {
  synchronized(lifecycle) {
   stopped = true; ready = false
   scheduled.values.forEach { it.cancel(false) }; scheduled.clear(); retryImmediately.clear()
   opening.values.forEach { runCatching { it.close() } }; opening.clear()
   routes.values().forEach { routes.remove(it.kind, it); it.close() }
   failReplies(); commands.clear(); synchronized(mediaCommands) { mediaCommands.clear() }; streams.clear(); service.features.disconnected()
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
   synchronized(mediaCommands) { mediaCommands[id]?.response=JSONObject(message.toString()) }
   val channel = commands.remove(id) ?: return false
   return channel.send(message, blocking)
  }
  return routes.select(if (message.optJSONObject("data")?.optString("kind")=="artwork") "files" else message.optString("feature"))?.send(message, blocking) ?: false
 }
 internal fun streamFailureReporter(id: String): (String) -> Unit {
  // Keep the command's original link after its initial success reply retires
  // commands[id]. A later playback error still belongs to that same session.
  val channel = commands[id] ?: error("Stream command disconnected")
  return { error ->
   commands.remove(id, channel)
   channel.send(JSONObject().put("type", "result").put("id", id)
    .put("ok", false).put("data", JSONObject()).put("error", error.take(180)))
  }
 }
 private fun connectedStatus() {
  val waiting = configured - routes.kinds()
  val suffix = if (waiting.isEmpty()) "" else " Reconnecting ${if (LinkKind.BLUETOOTH in waiting) "Bluetooth" else "Wi-Fi / LAN"}…"
  val hint = if (LinkKind.LAN in waiting && lanError.isNotBlank()) " Wi-Fi: $lanError" else ""
  service.status("Connected over $transportLabel.$suffix$hint", ConnectionPhase.CONNECTED)
 }
 private fun connect(kind: LinkKind, attempt: Int) {
  if (stopped) return
  val routeRevision=lanRouteRevision
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
   val context = PinnedTls.context(fingerprint)
   val candidates = if (kind==LinkKind.BLUETOOTH) listOf(TlsEndpoint("",prefs.getInt("port",17161),context,
    prefs.getString("bluetooth_address","").orEmpty(),service))
   else LanNetwork.endpoints(service,networkPaths.candidates(prefs.getString("host","").orEmpty(),prefs.getInt("port",17161))).map { (address,route) ->
    TlsEndpoint(address.host,address.port,context,android=service,network=route.network,connectTimeoutMs=2500,localAddress=route.localAddress)
   }
   check(candidates.isNotEmpty()) { "Waiting for this computer’s encrypted Wi-Fi addresses over Bluetooth" }
   var selected: TlsEndpoint? = null
   var connection: SecureChannel? = null
   var lastFailure: Exception? = null
   for (candidate in candidates) {
    check(!stopped) { "Connection cancelled" }
    check(kind!=LinkKind.LAN || routeRevision==lanRouteRevision) { "Wi-Fi/hotspot route changed" }
    if (kind==LinkKind.LAN) openingLanEndpoint=candidate
    try {
     connection=openControl(candidate) { socket -> opening[kind]=socket; if (stopped) { socket.close(); error("Connection cancelled") } }
     check(kind!=LinkKind.LAN || routeRevision==lanRouteRevision) { "Wi-Fi/hotspot route changed" }
     selected=candidate; break
    } catch (error: Exception) { lastFailure=error; runCatching { opening.remove(kind)?.close() } }
   }
   val destination=selected ?: throw (lastFailure ?: IllegalStateException("Computer connection unavailable"))
   val tls=checkNotNull(connection)
   opening[kind] = tls
   val input = DataInputStream(tls.inputStream); val output = DataOutputStream(tls.outputStream)
   Protocol.write(output, JSONObject().put("type", "hello").put("version", 1).put("device_id", service.credentials.id)
    .put("network_info",true).put("name", ((Build.MODEL ?: "Android") + service.getString(R.string.peer_name_suffix)).take(128)).put("capabilities", service.features.capabilities()))
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
    if (kind==LinkKind.LAN) lanError=""
    live.writer = Thread({
     try { while (live.open && !stopped) Protocol.write(output, live.outgoing.take()) }
     catch (_: Exception) { runCatching { tls.close() } }
    }, "unity-writer-${kind.name.lowercase()}").apply { start() }
    connectedStatus(); service.features.publish()
   }
   while (!stopped) {
    val message = Protocol.read(input)
    when (message.getString("type")) {
     "auth_request" -> {
      val authId=message.getString("id")
      service.features.authentication.receive(authId, if (message.isNull("challenge")) null else message.getJSONObject("challenge"), live) { signed ->
       live.send(JSONObject().put("type","auth_response").put("id",authId).put("signature",signed))
      }
     }
     "ping" -> check(live.send(JSONObject().put("type", "pong"))) { "Heartbeat queue full" }
     "pong" -> Unit
     "command" -> {
      val id = message.getString("id"); require(Protocol.validId(id))
      if (message.optString("feature") in listOf("media","sound","samsung_routines")) {
       val fingerprint=message.getString("feature")+":"+message.getString("action")+":"+message.getJSONObject("data").toString()
       val duplicate=synchronized(mediaCommands) {
        val old=mediaCommands[id]
        if (old!=null) {
         require(old.fingerprint==fingerprint) { "Command replay changed contents" }
         old.response?.let { live.send(it) }; true
        } else {
         while (mediaCommands.size>=256) { val completed=mediaCommands.entries.firstOrNull { it.value.response!=null } ?: error("Command history full"); mediaCommands.remove(completed.key) }
         mediaCommands[id]=MediaCommand(fingerprint); false
        }
       }
       if (duplicate) continue
      }
      check(commands.size < 256 || commands.containsKey(id)) { "Too many pending commands" }
      val previous = commands.putIfAbsent(id, live)
      require(previous == null || previous === live) { "Command changed transport" }
      val feature = message.optString("feature")
      service.dispatch(message)
      if (feature == "files" && message.optString("action") == "abort") commands.remove(id, live)
     }
     "capabilities" -> {
      val caps = message.getJSONArray("capabilities"); require(caps.length() <= 32)
      live.capabilities = caps
      service.features.media.remoteCapabilitiesChanged()
      if (!desktopAvailable("desktop_media")) service.features.desktopMedia.clear()
      if (!desktopAvailable("sound")) service.features.sound.clearDesktop()
      service.uiChanged()
     }
     "event" -> {
      if (message.optString("feature")=="connectivity" && live.open && routes.contains(live)) {
       // Only the authenticated, certificate-pinned computer can supply addresses.
       if (service.getSharedPreferences("settings",0).getBoolean("enable.connectivity",true) &&
        networkPaths.receive(message.getJSONObject("data"))) {
        val lan=routes.values().firstOrNull { it.kind==LinkKind.LAN }
        if(lan==null || !networkPaths.advertises(lan.endpoint.host,lan.endpoint.port)) retry(LinkKind.LAN,invalidate=true)
       }
      }
      // Only laptop-native metadata is accepted. Receiving it is never an action.
      if (message.optString("feature")=="desktop_media") service.features.desktopMedia.receive(message.getJSONObject("data"))
      if (message.optString("feature")=="sound") service.features.sound.receive(message.getJSONObject("data"))
     }
     "result" -> {
      val id = message.getString("id"); require(Protocol.validId(id))
      val pending = replies[id]
      if (pending != null && pending.channel === live && replies.remove(id, pending)) {
       if (pending.feature=="sound" && message.optBoolean("ok")) message.optJSONObject("data")?.optJSONObject("sound")?.let { service.features.sound.receive(it) }
       main.post { pending.callback(message.getBoolean("ok"), message.optString("error").take(512)) }
      }
     }
     else -> error("Unexpected protocol message")
    }
   }
  } catch (e: Exception) {
   failure = (e.message ?: "Connection lost").take(180)
   if (kind==LinkKind.LAN && !stopped) lanError = if (e is java.net.ConnectException || e is java.net.SocketTimeoutException)
    "Cannot reach the computer. Check Wi-Fi sign-in, its current address and firewall. Bluetooth can stay connected."
    else failure
  }
  finally {
   if (kind==LinkKind.LAN) openingLanEndpoint=null
   if (pairingHeld) pairing.unlock()
   synchronized(lifecycle) {
    channel?.let { lost ->
     service.features.authentication.disconnected(lost)
     routes.remove(kind, lost); lost.close(); failReplies(lost)
     commands.entries.toList().forEach { (id, origin) -> if (origin === lost && commands.remove(id, origin)) {
      synchronized(mediaCommands) { mediaCommands[id]?.let { if (it.response==null) it.response=JSONObject().put("type","result").put("id",id).put("ok",false).put("data",JSONObject()).put("error","Original command connection lost; action may have occurred") } }
      service.features.files.abort(id)
     } }
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
   synchronized(lifecycle) {
    running.remove(kind)
    val immediate = retryImmediately.remove(kind)
    val delay = if (immediate) 0L else (1L shl nextAttempt.coerceAtMost(6)) * 1000 + (0..500).random()
    schedule(kind, if (immediate) 0 else nextAttempt, delay)
   }
  }
 }
}
