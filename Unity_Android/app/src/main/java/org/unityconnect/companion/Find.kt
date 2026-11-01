package org.unityconnect.companion

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

internal interface FindSound {
 fun play()
 fun playing(): Boolean
 fun close()
}

class FindFeature internal constructor(service: CompanionService,
 private val createSound: (AudioAttributes) -> FindSound = { attributes ->
  val player = MediaPlayer()
  try {
   player.setAudioAttributes(attributes)
   player.setDataSource(service, Uri.parse("android.resource://${service.packageName}/${R.raw.unity_find}"))
   service.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { player.setPreferredDevice(it) }
   player.isLooping = true; player.setVolume(1f, 1f); player.prepare()
  } catch (e: Exception) { player.release(); throw e }
  object : FindSound {
   override fun play() = player.start()
   override fun playing() = player.isPlaying
   override fun close() { runCatching { player.stop() }; player.release() }
  }
 }) : Module(service, "find") {
 private val main = Handler(Looper.getMainLooper())
 private val audio = service.getSystemService(AudioManager::class.java)
 private val notifications = service.getSystemService(NotificationManager::class.java)
 private var sound: FindSound? = null
 private var originalVolume: Int? = null
 private var originalMuted = false
 private var raisedVolume = 0
 private val timeout = Runnable { stopNow() }
 private fun publish(state: String) { if (service.connection.ready) runCatching { service.event(id, JSONObject().put("state", state)) } }
 override fun command(action: String, data: JSONObject): JSONObject = onMain {
  when (action) {
   "stop" -> stopNow()
   "ring" -> {
    check(enabled()) { "Enable Find phone in Features" }
    check(audio.mode != AudioManager.MODE_IN_CALL && audio.mode != AudioManager.MODE_IN_COMMUNICATION) { "End the phone call before ringing this phone" }
    check(notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE) { "Do Not Disturb blocks alarms; allow alarms or turn it off on the phone" }
    stopNow()
    try {
     val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
     check(maximum > 0) { "Phone alarm output is unavailable" }
     originalVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
     originalMuted = audio.isStreamMute(AudioManager.STREAM_ALARM)
     raisedVolume = maxOf(originalVolume!!, (maximum * .8).toInt().coerceAtLeast(1))
     if (!audio.isVolumeFixed) {
      audio.setStreamVolume(AudioManager.STREAM_ALARM, raisedVolume, 0)
      if (audio.isStreamMute(AudioManager.STREAM_ALARM)) audio.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0)
     }
     check(audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0 && !audio.isStreamMute(AudioManager.STREAM_ALARM)) { "Phone alarm volume is muted or controlled by Android" }
     val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
     sound = createSound(attributes)
     sound!!.play(); check(sound!!.playing()) { "Phone could not play the find sound" }
     val stop = PendingIntent.getService(service, 4321, Intent(service, CompanionService::class.java).setAction("stop_find"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
     notifications.notify(4321, Notification.Builder(service, "unity.connection").setSmallIcon(R.drawable.ic_unity_symbolic)
      .setContentTitle("Find your phone").setContentText("Ringing for 30 seconds").setOngoing(true).setOnlyAlertOnce(true)
      .addAction(Notification.Action.Builder(null, "Stop ringing", stop).build()).build())
     main.postDelayed(timeout, 30000); publish("ringing")
    } catch (e: Exception) { stopNow(); throw e }
   }
   else -> error("Unsupported find action")
  }
  JSONObject().put("state", if (action == "ring") "ringing" else "stopped")
 }
 private fun stopNow() {
  main.removeCallbacks(timeout)
  sound?.let { runCatching { it.close() } }; sound = null
  notifications.cancel(4321)
  originalVolume?.let { previous ->
   // Keep changes made with the phone's controls while it was ringing.
   if (!audio.isVolumeFixed && audio.getStreamVolume(AudioManager.STREAM_ALARM) == raisedVolume) runCatching {
    audio.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0)
    if (originalMuted && !audio.isStreamMute(AudioManager.STREAM_ALARM)) audio.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_MUTE, 0)
   }
  }
  originalVolume = null; publish("stopped")
 }
 fun stop() { if (Looper.myLooper() == Looper.getMainLooper()) stopNow() else main.post { stopNow() } }
 fun settingsChanged() { if (!enabled()) stop() }
}
