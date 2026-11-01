package org.unityconnect.companion

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Captures permitted media playback only. Never selects a microphone source. */
internal class PhoneAudioCapture(private val service: CompanionService, val requestId: String,
 private val token: String, private val format: AudioWire.Format, private val endpoint: TlsEndpoint,
 private val ended: (PhoneAudioCapture, String) -> Unit) : Closeable {
 private val main = Handler(Looper.getMainLooper())
 private val closed = AtomicBoolean(false)
 private val queue = ArrayBlockingQueue<AudioWire.Packet>(3)
 private val reportFailure = service.connection.streamFailureReporter(requestId)
 private var projection: MediaProjection? = null
 private var recorder: AudioRecord? = null
 private var wifi: WifiManager.WifiLock? = null
 @Volatile private var socket: Closeable? = null
 private var reader: Thread? = null
 private var sender: Thread? = null
 private var consentOpen = false
 @Volatile var projectionForeground = false
  private set
 private val expired = Runnable { fail("Phone audio approval timed out") }
 fun requestConsent() {
  val open = PendingIntent.getActivity(service, 731, Intent(service, AudioConsentActivity::class.java)
   .putExtra("audio_request", requestId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  service.getSystemService(NotificationManager::class.java).notify(731, Notification.Builder(service, "unity.actions")
   .setSmallIcon(R.drawable.ic_media).setContentTitle("Approve phone audio sharing")
   .setContentText("Share media audio with your computer. Tap to approve.").setContentIntent(open).setAutoCancel(true).build())
  main.postDelayed(expired, 115000)
 }
 fun presentPending(activity: Activity) {
  if (!closed.get() && !projectionForeground && !consentOpen) {
   consentOpen = true
   activity.startActivity(Intent(activity, AudioConsentActivity::class.java).putExtra("audio_request", requestId))
  }
 }
 fun consent(result: Int, data: Intent?) {
  if (closed.get() || projectionForeground) return
  consentOpen = false
  if (result != Activity.RESULT_OK || data == null) { fail("Phone audio capture permission was denied"); return }
  try {
   check(service.connection.ready && service.features.audio.capability().optString("status") == "available") { "Audio is no longer enabled or connected" }
   check(service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Allow audio recording to share phone media" }
   main.removeCallbacks(expired)
   // The approved mediaProjection foreground type must precede getMediaProjection.
   projectionForeground = true; service.refreshForeground()
   val mp = checkNotNull(service.getSystemService(MediaProjectionManager::class.java).getMediaProjection(result, data))
   projection = mp
   mp.registerCallback(object : MediaProjection.Callback() {
    override fun onStop() { fail("Phone audio capture ended") }
   }, main)
   val config = AudioPlaybackCaptureConfiguration.Builder(mp)
    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(Process.myUid()).build()
   val minimum = AudioRecord.getMinBufferSize(format.rate, format.inputMask, AudioFormat.ENCODING_PCM_16BIT)
   check(minimum > 0) { "Phone audio capture format unavailable" }
   val input = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)
    .setAudioFormat(AudioFormat.Builder().setSampleRate(format.rate).setChannelMask(format.inputMask)
     .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(minimum, format.bytes * 4)).build()
   recorder = input
   check(input.state == AudioRecord.STATE_INITIALIZED) { "Phone audio capture unavailable" }
   input.startRecording()
   check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Phone audio capture could not start" }
   if (!endpoint.bluetooth) {
    val lock = service.applicationContext.getSystemService(WifiManager::class.java)
     .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Unity:phone-audio")
    lock.setReferenceCounted(false); runCatching { lock.acquire(); wifi = lock }
   }
   val stop = PendingIntent.getService(service, 732, Intent(service, CompanionService::class.java).setAction("stop_audio"), PendingIntent.FLAG_IMMUTABLE)
   service.getSystemService(NotificationManager::class.java).notify(731, Notification.Builder(service, "unity.actions")
    .setSmallIcon(R.drawable.ic_media).setContentTitle("Sharing phone audio")
    .setContentText("Media audio is playing on your computer. Some apps block capture.")
    .addAction(Notification.Action.Builder(null, "Stop", stop).build()).setOngoing(true).build())
   check(!closed.get()) { "Phone audio cancelled" }
   reader = Thread({
    try {
     Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
     var sequence = 0L
     while (!closed.get()) {
      val bytes = ByteArray(format.bytes); var offset = 0
      while (offset < bytes.size && !closed.get()) {
       val count = input.read(bytes, offset, bytes.size - offset, AudioRecord.READ_BLOCKING)
       check(count > 0 && count <= bytes.size - offset) { "Phone audio capture failed ($count)" }; offset += count
      }
      if (!closed.get()) {
       val packet = AudioWire.Packet(sequence++, bytes, SystemClock.elapsedRealtime())
       while (!queue.offer(packet)) queue.poll()
      }
     }
    } catch (e: Exception) { fail(e.message ?: "Phone audio capture failed") }
   }, "unity-phone-audio-capture").apply { start() }
   sender = Thread({
    try {
     val tls = endpoint.open(StreamKind.AUDIO, 15000) { opening -> socket = opening; if (closed.get()) opening.close() }
     socket = tls; check(!closed.get()) { "Phone audio cancelled" }
     val output = DataOutputStream(tls.outputStream)
     Protocol.write(output, JSONObject().put("type", "audio_hello").put("version", 1).put("device_id", service.credentials.id).put("token", token))
     check(Protocol.read(DataInputStream(tls.inputStream)).getString("type") == "ready") { "Laptop audio output unavailable" }
     var acknowledged = false
     while (!closed.get()) {
      val packet = queue.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: error("Phone audio capture stalled")
      if (SystemClock.elapsedRealtime() - packet.received > 100) continue
      val stalled = Runnable { fail("Phone audio network stalled; choose Wi-Fi or reconnect") }
      main.postDelayed(stalled, 1500)
      try { output.writeLong(packet.sequence); output.write(packet.pcm); output.flush() }
      finally { main.removeCallbacks(stalled) }
      if (!acknowledged) { acknowledged = true; service.result(requestId, true, JSONObject().put("state", "sending")); service.uiChanged() }
     }
    } catch (e: Exception) { fail(e.message ?: "Phone audio connection lost") }
   }, "unity-phone-audio-send").apply { start() }
   service.uiChanged()
  } catch (e: Exception) { fail(e.message ?: "Phone audio capture failed") }
 }
 private fun fail(error: String) {
  if (!closed.compareAndSet(false, true)) return
  runCatching { reportFailure(error.take(180)) }
  ended(this, error)
 }
 override fun close() {
  closed.set(true); main.removeCallbacks(expired)
  runCatching { socket?.close() }; reader?.interrupt(); sender?.interrupt(); queue.clear()
  val release = Runnable {
   runCatching { recorder?.stop() }; runCatching { recorder?.release() }; recorder = null
   runCatching { projection?.stop() }; projection = null
   wifi?.let { runCatching { if (it.isHeld) it.release() } }; wifi = null
  }
  projectionForeground = false
  if (Looper.myLooper() == Looper.getMainLooper()) release.run() else main.post(release)
  service.getSystemService(NotificationManager::class.java).cancel(731)
 }
}

class AudioConsentActivity : Activity() {
 override fun onCreate(saved: Bundle?) {
  super.onCreate(saved)
  val id = intent.getStringExtra("audio_request")
  if (id == null || id != CompanionService.instance?.features?.audio?.pendingId()) { finish(); return }
  if (saved == null) {
   if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 733)
   else project()
  }
 }
 private fun project() {
  if (intent.getStringExtra("audio_request") != CompanionService.instance?.features?.audio?.pendingId()) { finish(); return }
  startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 734)
 }
 override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, grants: IntArray) {
  super.onRequestPermissionsResult(request, permissions, grants)
  if (request == 733) {
   if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) project()
   else { CompanionService.instance?.features?.audio?.consent(intent.getStringExtra("audio_request"), RESULT_CANCELED, null); finish() }
  }
 }
 @Deprecated("Platform result callback")
 override fun onActivityResult(request: Int, result: Int, data: Intent?) {
  super.onActivityResult(request, result, data)
  if (request == 734) { CompanionService.instance?.features?.audio?.consent(intent.getStringExtra("audio_request"), result, data); finish() }
 }
}
