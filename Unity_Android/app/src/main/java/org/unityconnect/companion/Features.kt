package org.unityconnect.companion

import android.Manifest
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.media.session.*
import android.net.*
import android.os.*
import android.provider.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

fun cap(id: String, status: String = "available", reason: String = "") = JSONObject().put("id", id).put("status", status).put("reason", reason)
interface Feature {
 val id: String
 fun capability(): JSONObject
 fun command(action: String, data: JSONObject): JSONObject?
}
abstract class Module(protected val service: CompanionService, final override val id: String) : Feature {
 protected fun granted(permission: String) = service.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
 protected fun enabled() = service.getSharedPreferences("settings", 0).getBoolean("enable.$id", !sensitive())
 private fun sensitive() = id in listOf("notifications", "notification_actions", "sms", "call_notifications", "call_control", "clipboard", "files", "photos", "browsing", "media", "audio", "remote_input", "lock", "screen")
 protected fun status(ok: Boolean = true, reason: String = "") = when {
  !enabled() -> cap(id, "disabled", "Enable this feature on your phone")
  !ok -> cap(id, "permission_required", reason)
  else -> cap(id, reason = reason)
 }
 override fun capability() = status()
 override fun command(action: String, data: JSONObject): JSONObject? { error("Unsupported action") }
 protected fun onMain(block: () -> JSONObject): JSONObject {
  if (Looper.myLooper() == Looper.getMainLooper()) return block()
  val future = CompletableFuture<JSONObject>()
  Handler(Looper.getMainLooper()).post { try { future.complete(block()) } catch (e: Exception) { future.completeExceptionally(e) } }
  return future.get(5, TimeUnit.SECONDS)
 }
}
class Unsupported(service: CompanionService, id: String, private val reason: String) : Module(service, id) {
 override fun capability() = cap(id, "unsupported", reason)
}
class LocalFeature(service: CompanionService, id: String) : Module(service, id)

class Battery(service: CompanionService) : Module(service, "battery") {
 private val manager = service.getSystemService(BatteryManager::class.java)
 private val estimator = BatteryEstimator()
 private val main = Handler(Looper.getMainLooper())
 private var watching = false
 private val update = object : Runnable {
  override fun run() {
   if (!watching) return
   if (!service.connection.ready || !enabled()) { stop(); return }
   publish()
   main.postDelayed(this, 30_000)
  }
 }
 fun watch() { main.post { if (!watching) { watching = true; main.postDelayed(update, 30_000) } } }
 fun stop() { main.post { watching = false; main.removeCallbacks(update); estimator.reset() } }
 fun publish(intent: Intent? = null) {
  if (capability().getString("status") != "available" || !service.connection.ready) return
  val i = intent ?: service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
  val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
  if (level < 0 || scale <= 0 || level > scale) return
  val state = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
  val status = when (state) {
   BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
   BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
   BatteryManager.BATTERY_STATUS_FULL -> "full"
   BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
   else -> "unknown"
  }
  fun property(id: Int) = runCatching { manager.getIntProperty(id) }.getOrDefault(Int.MIN_VALUE)
  val reading = BatteryReading((level.toLong() * 100 / scale).toInt(), status,
   i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0, i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
   property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW), property(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
   property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER), runCatching { manager.computeChargeTimeRemaining() }.getOrDefault(-1))
  runCatching { service.event(id, estimator.sample(reading, SystemClock.elapsedRealtime())) }
 }
 override fun command(action: String, data: JSONObject): JSONObject { require(action == "refresh"); publish(); return JSONObject().put("state", "refreshed") }
}
class NetworkStatus(service: CompanionService) : Module(service, "network") {
 private val telephony = service.getSystemService(android.telephony.TelephonyManager::class.java)
 private var listening = false
 private val listener = object : android.telephony.PhoneStateListener(service.mainExecutor) {
  override fun onSignalStrengthsChanged(signal: android.telephony.SignalStrength?) { publish() }
  override fun onServiceStateChanged(state: android.telephony.ServiceState?) { publish() }
 }
 fun watch() {
  Handler(Looper.getMainLooper()).post {
   if (!listening && granted(Manifest.permission.READ_PHONE_STATE) && enabled()) {
    runCatching { telephony.listen(listener, android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or android.telephony.PhoneStateListener.LISTEN_SERVICE_STATE); listening = true }
   }
  }
 }
 fun stop() { Handler(Looper.getMainLooper()).post { if (listening) runCatching { telephony.listen(listener, android.telephony.PhoneStateListener.LISTEN_NONE) }; listening = false } }

 fun publish() {
  if (!service.connection.ready || capability().getString("status") != "available") return
  val cm = service.getSystemService(ConnectivityManager::class.java)
  val nc = cm.getNetworkCapabilities(cm.activeNetwork)
  val kind = when { nc == null -> "none"; nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"; nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"; nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"; else -> "other" }
  val tm = service.getSystemService(android.telephony.TelephonyManager::class.java)
  val signal = if (granted(Manifest.permission.READ_PHONE_STATE)) runCatching { tm.signalStrength?.level }.getOrNull() else null
  runCatching { service.event(id, JSONObject().put("kind", kind).put("signal", signal ?: JSONObject.NULL).put("operator", tm.networkOperatorName.take(128)).put("internet", nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false)) }
 }
 override fun command(action: String, data: JSONObject): JSONObject { require(action == "refresh"); publish(); return JSONObject().put("state", "refreshed") }
}
class ClipboardFeature(service: CompanionService) : Module(service, "clipboard") {
 override fun capability() = status(reason = "Manual sync while Unity is visible; Android restricts background clipboard reads")
 override fun command(action: String, data: JSONObject): JSONObject {
  require(action == "set"); val text = data.getString("text"); require(text.toByteArray().size <= 64 * 1024 - 256)
  service.getSharedPreferences("pending", 0).edit().putString("clipboard", text).commit()
  service.uiChanged()
  ReceivedItems.text(service, text)
  return JSONObject().put("state", "user_action_required").put("reason", "Tap the received text notification to open or copy it")
 }
}
class UrlFeature(service: CompanionService) : Module(service, "urls") {
 override fun command(action: String, data: JSONObject): JSONObject {
  require(action == "open"); val value = data.getString("url"); val uri = Uri.parse(value)
  require(value.length <= 8192 && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && value.none { it.code < 32 })
  service.getSharedPreferences("pending", 0).edit().putString("url", value).commit(); service.uiChanged(); ReceivedItems.link(service, value)
  return JSONObject().put("state", "user_action_required").put("reason", "Tap the received link notification to open it")
 }
}
class RingerFeature(service: CompanionService) : Module(service, "ringer") {
 override fun capability() = status(service.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted, "Allow Do Not Disturb access on phone")
 override fun command(action: String, data: JSONObject): JSONObject = onMain {
  require(action == "set"); val mode = data.getString("mode")
  val am = service.getSystemService(AudioManager::class.java); val nm = service.getSystemService(NotificationManager::class.java)
  when (mode) {
   "normal" -> am.ringerMode = AudioManager.RINGER_MODE_NORMAL
   "silent" -> am.ringerMode = AudioManager.RINGER_MODE_SILENT
   "vibrate" -> am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
   "dnd" -> nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
   "off" -> nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
   else -> error("Unsupported ringer mode")
  }
  service.event(id, JSONObject().put("mode", mode)); JSONObject().put("state", "applied").put("mode", mode)
 }
}
class FindFeature(service: CompanionService) : Module(service, "find") {
 private var ringtone: Ringtone? = null
 override fun command(action: String, data: JSONObject): JSONObject = onMain {
  when (action) {
   "ring" -> { ringtone?.stop(); ringtone = RingtoneManager.getRingtone(service, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)); check(ringtone != null); ringtone?.play(); Handler(Looper.getMainLooper()).postDelayed({ ringtone?.stop() }, 30000) }
   "stop" -> ringtone?.stop()
   else -> error("Unsupported action")
  }
  JSONObject().put("state", if (action == "ring") "ringing" else "stopped")
 }
 fun stop() { Handler(Looper.getMainLooper()).post { ringtone?.stop() } }
}
class HotspotFeature(service: CompanionService) : Module(service, "hotspot") {
 override fun capability() = if (enabled()) cap(id, "user_action_required", "Phone must approve tethering; no silent activation or Wi-Fi Direct assumption") else status()
 override fun command(action: String, data: JSONObject): JSONObject {
  require(action == "request_settings"); service.prompt("Review internet sharing settings", "hotspot")
  return JSONObject().put("state", "user_action_required").put("reason", "Open tethering settings from the phone notification")
 }
}
class FeatureRegistry(val service: CompanionService) {
 val battery = Battery(service)
 val network = NetworkStatus(service)
 val calls = CallNotifications(service)
 val callControl = CallControl(service)
 val files = FileFeature(service)
 val find = FindFeature(service)
 val media = MediaFeature(service)
 val audio = AudioRouting(service)
 val lock = DeviceLock(service)
 val screen = ScreenSharing(service)
 val sms = SmsFeature(service)
 val modules: Map<String, Feature> = listOf(
  LocalFeature(service, "pairing"), LocalFeature(service, "reconnect"), battery, network,
  object : Module(service, "connectivity") { override fun capability() = cap(id, reason = "Pinned TLS over simultaneous Bluetooth and Wi-Fi / LAN connections") },
  NotificationFeature(service), NotificationActions(service), sms, calls,
  callControl,
  ClipboardFeature(service), files, PhotoFeature(service), UrlFeature(service), BrowseFeature(service), media, audio, lock, screen,
  RingerFeature(service), find, RemoteInputFeature(service),
  HotspotFeature(service)
 ).associateBy { it.id }
 fun capabilities() = JSONArray(modules.values.map { it.capability() })
 fun publish() {
  audio.settingsChanged(); screen.settingsChanged()
  if (!service.connection.ready) return
  service.connection.send(JSONObject().put("type", "capabilities").put("capabilities", capabilities()))
  battery.publish(); battery.watch(); network.watch(); network.publish(); media.watch(); sms.watch(); callControl.publish(); PhoneNotifications.instance?.publishAll()
 }
 fun disconnected() { battery.stop(); audio.stop(); screen.stop(); files.abortAll(); find.stop(); media.stop(); sms.stop(); network.stop() }
}
