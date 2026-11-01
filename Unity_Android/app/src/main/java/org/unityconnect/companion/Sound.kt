package org.unityconnect.companion

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import kotlin.math.roundToInt

/** Phone music volume and display-only laptop volume; snapshots never call setters. */
class SoundFeature(service: CompanionService): Module(service,"sound") {
 private val audio=service.getSystemService(AudioManager::class.java)
 private val main=Handler(Looper.getMainLooper())
 private var watching=false
 private var lastWire=""
 @Volatile private var phone="{}"
 @Volatile private var desktop="{}"
 override fun capability()=if (service.getSharedPreferences("settings",0).getBoolean("enable.media",false)) cap(id) else cap(id,"disabled","Enable Media and sound controls on your phone")
 fun phoneState(): JSONObject=if (capability().optString("status")=="available") JSONObject(phone) else JSONObject()
 fun desktopState(): JSONObject=if (capability().optString("status")=="available" && service.connection.ready && service.connection.desktopAvailable(id)) JSONObject(desktop) else JSONObject()
 private fun state(): JSONObject {
  val min=audio.getStreamMinVolume(AudioManager.STREAM_MUSIC);val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
  val current=audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(min,max.coerceAtLeast(min))
  val supported=!audio.isVolumeFixed && max>min
  return JSONObject().put("origin","phone").put("available",supported).put("name","Phone media")
   .put("volume",if (max>min) ((current-min)*100.0/(max-min)).roundToInt().coerceIn(0,100) else 0)
   .put("muted",audio.isStreamMute(AudioManager.STREAM_MUSIC)).put("error",if (supported) "" else "Android manages volume on this device")
 }
 private fun publish() {
  if (capability().optString("status")!="available") { clear();return }
  val current=state().toString()
  if (phone!=current) { phone=current;service.uiChanged() }
  if (service.connection.ready && current!=lastWire && runCatching { service.event(id,JSONObject(current));true }.getOrDefault(false)) lastWire=current
 }
 private val tick=object: Runnable { override fun run() { if (watching) { runCatching { publish() }; main.postDelayed(this,1000) } } }
 fun watch() { main.post {
  main.removeCallbacks(tick);watching=capability().optString("status")=="available";lastWire=""
  if (watching) { runCatching { publish() };main.postDelayed(tick,1000) } else clear()
 } }
 fun stop() {
  // Clear before a new transport can deliver its initial laptop snapshot.
  // A delayed old-connection stop must not erase that newer state.
  clear()
  main.post { watching=false;main.removeCallbacks(tick);lastWire="" }
 }
 private fun clear() { phone="{}";desktop="{}";service.uiChanged() }
 fun clearDesktop() { desktop="{}";service.uiChanged() }
 fun receive(data: JSONObject) {
  if (data.optString("origin")!="desktop" || capability().optString("status")!="available") return
  require(data.toString().toByteArray().size<=4096 && data.get("available") is Boolean && data.get("muted") is Boolean)
  require((data.get("volume") is Int || data.get("volume") is Long) && data.getLong("volume") in 0..100)
  require(data.optString("name").toByteArray().size<=512 && data.optString("error").toByteArray().size<=512)
  if (data.getBoolean("available")) require(Protocol.validId(data.getString("output")))
  val value=data.toString();if (desktop!=value) { desktop=value;service.uiChanged() }
 }
 override fun command(action: String,data: JSONObject): JSONObject=onMain {
  check(capability().optString("status")=="available") { "Enable Media and sound controls" }
  require(data.getString("source")=="phone") { "Laptop volume cannot be forwarded through phone commands" }
  if (action=="refresh") { publish();return@onMain JSONObject().put("state","refreshed") }
  check(state().getBoolean("available")) { "Android manages volume on this device" }
  when (action) {
   "volume"->{
    require((data.get("volume") is Int || data.get("volume") is Long))
    val raw=data.getLong("volume");require(raw in 0..100);val percent=raw.toInt()
    val min=audio.getStreamMinVolume(AudioManager.STREAM_MUSIC);val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    check(max>min) { "Phone media volume is unavailable" }
    val target=(min+(max-min)*percent/100.0).roundToInt().coerceIn(min,max)
    if (audio.getStreamVolume(AudioManager.STREAM_MUSIC)!=target) audio.setStreamVolume(AudioManager.STREAM_MUSIC,target,0)
   }
   "mute"->{
    require(data.get("muted") is Boolean);val mute=data.getBoolean("muted")
    if (audio.isStreamMute(AudioManager.STREAM_MUSIC)!=mute) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,0)
   }
   else->error("Unsupported sound control")
  }
  publish();JSONObject().put("state","submitted_to_phone_media_volume")
 }
}
