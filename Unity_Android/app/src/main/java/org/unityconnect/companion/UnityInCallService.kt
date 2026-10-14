package org.unityconnect.companion

import android.Manifest
import android.app.*
import android.app.role.RoleManager
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.telecom.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Optional default Phone integration. Local calls work without the companion connection. */
@Suppress("DEPRECATION")
class UnityInCallService : InCallService() {
 companion object {
  var instance: UnityInCallService? = null
   private set
  const val CHANNEL = "unity.phone.calls"
  const val NOTIFICATION = 25
  fun enabled(context: Context) = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)
  fun emptySnapshot() = JSONObject().put("backend", "android_telecom").put("calls", JSONArray()).put("muted", false).put("route", "phone").put("routes", JSONArray())
 }
 private val live = linkedMapOf<String, Call>()
 private val callbacks = mutableMapOf<Call, Call.Callback>()
 private var audio: CallAudioState? = null
 private var endpoints = listOf<CallEndpoint>()
 private var endpoint: CallEndpoint? = null
 private var microphoneMuted = false
 private var routeError = ""
 override fun onCreate() {
  super.onCreate(); instance = this
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Phone calls", NotificationManager.IMPORTANCE_HIGH))
 }
 override fun onCallAdded(call: Call) {
  super.onCallAdded(call)
  live[UUID.randomUUID().toString().replace("-", "")] = call
  val callback = object : Call.Callback() {
   override fun onStateChanged(call: Call, state: Int) { changed() }
   override fun onDetailsChanged(call: Call, details: Call.Details) { changed() }
   override fun onChildrenChanged(call: Call, children: MutableList<Call>) { changed() }
  }
  callbacks[call] = callback; call.registerCallback(callback, Handler(Looper.getMainLooper()))
  changed()
  runCatching { startActivity(Intent(this, PhoneCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
 }
 override fun onCallRemoved(call: Call) {
  callbacks.remove(call)?.let { call.unregisterCallback(it) }
  live.entries.removeAll { it.value === call }
  if (live.isEmpty()) routeError = ""
  changed(); super.onCallRemoved(call)
 }
 override fun onCallAudioStateChanged(state: CallAudioState) {
  audio = state; microphoneMuted = state.isMuted; changed()
 }
 override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
  endpoints = availableEndpoints.toList(); changed()
 }
 override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
  endpoint = callEndpoint; routeError = ""; changed()
 }
 override fun onMuteStateChanged(isMuted: Boolean) { microphoneMuted = isMuted; changed() }
 private fun endpointRoute(type: Int) = when (type) {
  CallEndpoint.TYPE_SPEAKER -> "speaker"
  CallEndpoint.TYPE_BLUETOOTH -> "bluetooth"
  CallEndpoint.TYPE_WIRED_HEADSET -> "wired"
  else -> "phone"
 }
 private fun legacyRoute(route: Int) = when (route) {
  CallAudioState.ROUTE_SPEAKER -> "speaker"
  CallAudioState.ROUTE_BLUETOOTH -> "bluetooth"
  CallAudioState.ROUTE_WIRED_HEADSET -> "wired"
  else -> "phone"
 }
 fun snapshot(): JSONObject {
  val calls = JSONArray()
  live.forEach { (id, call) ->
   if (call.state != Call.STATE_DISCONNECTED) calls.put(JSONObject().put("id", id).put("state", callState(call.state))
    .put("number", Protocol.text(call.details.handle?.schemeSpecificPart.orEmpty(), 64))
    .put("name", Protocol.text(call.details.callerDisplayName.orEmpty(), 256))
    .put("connect_time", call.details.connectTimeMillis.coerceAtLeast(0))
    .put("can_hold", call.details.can(Call.Details.CAPABILITY_HOLD)).put("conference", call.details.hasProperty(Call.Details.PROPERTY_CONFERENCE)))
  }
  val routes = if (Build.VERSION.SDK_INT >= 34 && endpoints.isNotEmpty()) endpoints.map { endpointRoute(it.endpointType) }.distinct() else
   listOf("phone" to CallAudioState.ROUTE_EARPIECE, "speaker" to CallAudioState.ROUTE_SPEAKER, "wired" to CallAudioState.ROUTE_WIRED_HEADSET, "bluetooth" to CallAudioState.ROUTE_BLUETOOTH)
    .filter { (_, mask) -> (audio?.supportedRouteMask ?: 0) and mask != 0 }.map { it.first }
  return JSONObject().put("backend", "android_telecom").put("calls", calls).put("muted", microphoneMuted)
   .put("route", if (Build.VERSION.SDK_INT >= 34 && endpoint != null) endpointRoute(endpoint!!.endpointType) else legacyRoute(audio?.route ?: CallAudioState.ROUTE_EARPIECE))
   .put("routes", JSONArray(routes)).put("error", routeError)
 }
 fun control(action: String, data: JSONObject, remote: Boolean = false): JSONObject {
  check(Looper.myLooper() == Looper.getMainLooper())
  val calls = live.values.filter { it.state != Call.STATE_DISCONNECTED }
  check(calls.isNotEmpty()) { "The call has ended" }
  if (remote) check(calls.none { it.details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL) || it.details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) ||
   it.details.handle?.schemeSpecificPart?.let { number -> getSystemService(android.telephony.TelephonyManager::class.java).isEmergencyNumber(number) } == true }) { "Use the phone directly for emergency calls" }
  if (action == "route") return route(data.getString("route"))
  if (action == "mute") { setMuted(data.getBoolean("muted")); return JSONObject().put("state", "requested") }
  val call = data.optString("call_id").takeIf { it.isNotEmpty() }?.let { live[it] }
   ?: if (!data.has("call_id") && calls.size == 1) calls.first() else error("This call is no longer available")
  requireCallAction(action, call.state, call.details.callCapabilities)
  when (action) {
   "answer" -> call.answer(VideoProfile.STATE_AUDIO_ONLY)
   "reject" -> call.reject(false, null)
   "hangup" -> call.disconnect()
   "hold" -> call.hold()
   "unhold" -> call.unhold()
   "dtmf" -> {
    val tone = data.getString("tone"); require(tone.length == 1 && tone[0] in "0123456789*#ABCD") { "Invalid keypad tone" }
    call.playDtmfTone(tone[0]); Handler(Looper.getMainLooper()).postDelayed({ call.stopDtmfTone() }, 200)
   }
  }
  return JSONObject().put("state", "requested")
 }
 private fun route(route: String): JSONObject {
  require(route in listOf("phone", "speaker", "wired", "bluetooth", "desktop")) { "Invalid call audio route" }
  val requested = if (route == "desktop") "bluetooth" else route
  check(snapshot().getJSONArray("routes").let { list -> (0 until list.length()).any { list.getString(it) == requested } }) { "This call audio device is unavailable" }
  routeError = ""
  if (route == "desktop") {
   check(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 31) { "Allow nearby devices access" }
   val address = getSharedPreferences("settings", MODE_PRIVATE).getString("bluetooth_address", "").orEmpty()
   val device = audio?.supportedBluetoothDevices?.singleOrNull { it.address.equals(address, true) }
    ?: error("Pair your saved computer for Bluetooth calls first")
   requestBluetoothAudio(device)
  } else if (Build.VERSION.SDK_INT >= 34 && endpoints.isNotEmpty()) {
   val target = endpoints.firstOrNull { endpointRoute(it.endpointType) == requested } ?: error("Call audio device disconnected")
   requestCallEndpointChange(target, mainExecutor, object : OutcomeReceiver<Void, CallEndpointException> {
    override fun onResult(result: Void?) { routeError = ""; changed() }
    override fun onError(error: CallEndpointException) { routeError = "Audio route failed: ${error.message.orEmpty().take(140)}"; changed() }
   })
  } else setAudioRoute(when (requested) {
   "speaker" -> CallAudioState.ROUTE_SPEAKER
   "wired" -> CallAudioState.ROUTE_WIRED_HEADSET
   "bluetooth" -> CallAudioState.ROUTE_BLUETOOTH
   else -> CallAudioState.ROUTE_EARPIECE
  })
  return JSONObject().put("state", "requested")
 }
 private fun changed() {
  updateNotification()
  runCatching { CompanionService.instance?.features?.callControl?.publish() }
  CompanionEvents.changed()
 }
 private fun updateNotification() {
  val manager = getSystemService(NotificationManager::class.java)
  val current = live.entries.firstOrNull { it.value.state == Call.STATE_RINGING } ?: live.entries.firstOrNull { it.value.state != Call.STATE_DISCONNECTED }
  if (current == null) { manager.cancel(NOTIFICATION); return }
  val call = current.value
  val open = PendingIntent.getActivity(this, 25, Intent(this, PhoneCallActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_phone).setCategory(Notification.CATEGORY_CALL)
   .setContentTitle(call.details.callerDisplayName?.takeIf { it.isNotEmpty() } ?: call.details.handle?.schemeSpecificPart ?: "Unknown caller")
   .setContentText(if (call.state == Call.STATE_RINGING) "Incoming call" else "Call in progress").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
  if (call.state == Call.STATE_RINGING) notification.setFullScreenIntent(open, true)
  if (call.details.connectTimeMillis > 0) notification.setWhen(call.details.connectTimeMillis).setUsesChronometer(true)
  val actions = if (call.state == Call.STATE_RINGING) listOf("answer" to "Answer", "reject" to "Decline") else listOf("hangup" to "End call")
  actions.forEachIndexed { index, (action, title) ->
   val intent = Intent(this, PhoneCallAction::class.java).setAction(action).putExtra("call_id", current.key)
   notification.addAction(Notification.Action.Builder(null, title, PendingIntent.getBroadcast(this, index + 30, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
  }
  manager.notify(NOTIFICATION, notification.build())
 }
 override fun onDestroy() {
  callbacks.forEach { (call, callback) -> call.unregisterCallback(callback) }; callbacks.clear(); live.clear()
  instance = null; getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
  runCatching { CompanionService.instance?.features?.callControl?.publish() }; CompanionEvents.changed(); super.onDestroy()
 }
}

class PhoneCallAction : BroadcastReceiver() {
 override fun onReceive(context: Context, intent: Intent) {
  val action = intent.action ?: return
  if (action !in listOf("answer", "reject", "hangup")) return
  runCatching { UnityInCallService.instance?.control(action, JSONObject().put("call_id", intent.getStringExtra("call_id"))) }
 }
}

/** The optional Phone role must provide a call screen even offline or with sharing disabled. */
class PhoneCallActivity : Activity() {
 private lateinit var body: android.widget.LinearLayout
 private var signature = ""
 private val timers = mutableListOf<Pair<android.widget.TextView, Long>>()
 private val handler = Handler(Looper.getMainLooper())
 private val observer: () -> Unit = { render() }
 private val tick = object : Runnable {
  override fun run() {
   timers.forEach { (view, start) ->
    val elapsed = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
    view.text = "%02d:%02d".format(elapsed / 60, elapsed % 60)
   }; handler.postDelayed(this, 1000)
  }
 }
 override fun onCreate(saved: Bundle?) {
  super.onCreate(saved); setShowWhenLocked(true); setTurnScreenOn(true)
  val ui = AdwaitaUi(this); body = ui.column().apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(24)); setBackgroundColor(ui.window) }
  setContentView(android.widget.ScrollView(this).apply { addView(body) }); render()
 }
 override fun onStart() { super.onStart(); CompanionEvents.observe(observer); render(); handler.post(tick) }
 override fun onStop() { CompanionEvents.remove(observer); handler.removeCallbacks(tick); super.onStop() }
 private fun action(title: String, action: String, data: JSONObject = JSONObject()) {
  body.addView(android.widget.Button(this).apply { text = title; setOnClickListener {
   runCatching { UnityInCallService.instance?.control(action, data) }.onFailure { android.widget.Toast.makeText(this@PhoneCallActivity, it.message, android.widget.Toast.LENGTH_LONG).show() }
  } })
 }
 private fun render() {
  val service = UnityInCallService.instance
  val state = service?.snapshot() ?: UnityInCallService.emptySnapshot()
  val calls = state.getJSONArray("calls")
  if (calls.length() == 0) { finish(); return }
  if (signature == state.toString()) return
  signature = state.toString(); body.removeAllViews(); timers.clear()
  val ui = AdwaitaUi(this)
  for (index in 0 until calls.length()) {
   val call = calls.getJSONObject(index); val id = call.getString("id"); val phase = call.getString("state")
   body.addView(ui.label(call.getString("name").ifEmpty { call.getString("number").ifEmpty { "Unknown caller" } }, 26f, true))
   body.addView(ui.label(phase.replace('_', ' '), 16f))
   if (call.getLong("connect_time") > 0) { val timer = ui.label("00:00", 26f, true); body.addView(timer); timers.add(timer to call.getLong("connect_time")) }
   if (phase == "ringing") { action("Answer", "answer", JSONObject().put("call_id", id)); action("Decline", "reject", JSONObject().put("call_id", id)) }
   else {
    action("End call", "hangup", JSONObject().put("call_id", id))
    if (call.getBoolean("can_hold") && phase in listOf("active", "held")) action(if (phase == "held") "Resume" else "Hold", if (phase == "held") "unhold" else "hold", JSONObject().put("call_id", id))
    if (phase == "active") {
     val grid = android.widget.GridLayout(this).apply { columnCount = 3 }
     "123456789*0#".forEach { tone -> grid.addView(android.widget.Button(this).apply { text = tone.toString(); setOnClickListener {
      runCatching { service?.control("dtmf", JSONObject().put("call_id", id).put("tone", tone.toString())) }.onFailure { android.widget.Toast.makeText(this@PhoneCallActivity, it.message, android.widget.Toast.LENGTH_LONG).show() }
     } }) }
     body.addView(grid)
    }
   }
  }
  action(if (state.getBoolean("muted")) "Unmute" else "Mute", "mute", JSONObject().put("muted", !state.getBoolean("muted")))
  body.addView(ui.label("Audio: ${state.getString("route")}", 18f, true))
  val routes = state.getJSONArray("routes")
  for (index in 0 until routes.length()) { val route = routes.getString(index); action(when (route) { "speaker" -> "Phone speakerphone"; "bluetooth" -> "Bluetooth"; "wired" -> "Wired headset"; else -> "Phone earpiece" }, "route", JSONObject().put("route", route)) }
  if (state.optString("error").isNotEmpty()) body.addView(ui.label(state.getString("error"), 16f))
 }
}
