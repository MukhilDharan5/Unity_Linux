package org.unityconnect.companion

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.Closeable
import java.io.EOFException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed 10 ms PCM packets; no JSON or file chunks on the audio path after Ready. */
internal object AudioWire {
 const val RATE = 48000
 const val CHANNELS = 2
 const val FRAMES = 480
 const val BYTES = FRAMES * CHANNELS * 2
 data class Format(val rate: Int, val channels: Int, val frames: Int) {
  val bytes get() = frames * channels * 2
  val outputMask get() = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
  val inputMask get() = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
 }
 fun validate(data: JSONObject): Format {
  val format = Format(data.getInt("rate"), data.getInt("channels"), data.getInt("frames"))
  require(format in listOf(Format(RATE, CHANNELS, FRAMES), Format(16000, 1, 160)) && data.getString("format") == "s16le") { "Unsupported audio format" }
  val token = data.getString("token")
  require(token.length == 64 && Protocol.unhex(token).size == 32) { "Invalid audio token" }
  require(Protocol.validId(data.getString("request_id"))) { "Invalid audio request" }
  require(data.optString("source", "desktop") in listOf("desktop", "phone")) { "Invalid audio source" }
  return format
 }
 data class Packet(val sequence: Long, val pcm: ByteArray, val received: Long)
 fun read(input: DataInputStream, previous: Long, bytes: Int = BYTES): Packet {
  val sequence = input.readLong()
  require(sequence >= 0 && sequence > previous) { "Invalid audio packet sequence" }
  val pcm = ByteArray(bytes); input.readFully(pcm)
  return Packet(sequence, pcm, SystemClock.elapsedRealtime())
 }
}

/** One output owns playback timing. A missing network packet is an underrun,
 * not a reason to pause/flush a working AudioTrack on every sequence gap. */
internal class PcmPlayback(private val play: () -> Unit, private val pause: () -> Unit,
 private val flush: () -> Unit, private val write: (ByteArray, Int, Int) -> Int) {
 private var started = false
 @Volatile var suspended = false
 fun accept(packet: AudioWire.Packet, now: Long, open: () -> Boolean): Boolean {
  if (suspended) {
   if (started) { pause(); flush(); started = false }
   return false
  }
  if (now - packet.received > 100 || !open()) return false
  // Start before a blocking write: some outputs have a buffer smaller than
  // one packet, and a stopped output cannot drain that write.
  if (!started) { play(); started = true }
  var offset = 0
  while (offset < packet.pcm.size && open()) {
   val count = write(packet.pcm, offset, packet.pcm.size - offset)
   check(count > 0 && count <= packet.pcm.size - offset) { "Phone audio playback failed ($count)" }
   offset += count
  }
  return offset == packet.pcm.size
 }
}

class AudioRouting(service: CompanionService) : Module(service, "audio") {
 @Volatile private var current: Playback? = null
 @Volatile private var capture: PhoneAudioCapture? = null
 fun active() = current != null
 fun capturing() = capture != null
 val projectionForeground get() = capture?.projectionForeground == true
 fun pendingId() = capture?.takeIf { !it.projectionForeground }?.requestId
 fun presentPending(activity: android.app.Activity) { capture?.presentPending(activity) }
 fun consent(id: String?, result: Int, data: android.content.Intent?) { capture?.takeIf { it.requestId == id }?.consent(result, data) }
 fun stopRemote() {
  if (service.connection.ready && service.connection.desktopAvailable("audio")) {
   runCatching { service.connection.request("audio", "stop") { _, _ -> stop() } }.onFailure { stop() }
  } else stop()
 }
 private fun captureEnded(session: PhoneAudioCapture, error: String) {
  synchronized(this) { if (capture !== session) return; capture = null; lastError = error.take(180) }
  session.close(); service.refreshForeground(); service.uiChanged()
 }
 @Volatile var playing = false
  private set
 @Volatile var lastError = ""
  private set
 @Synchronized override fun command(action: String, data: JSONObject): JSONObject? {
  when (action) {
   "start" -> {
    val format = AudioWire.validate(data)
    check(enabled()) { "Enable Phone and laptop audio in Features" }
    check(current == null && capture == null) { "Stop the current audio stream first" }
    if (data.optString("source", "desktop") == "phone") {
     val session = PhoneAudioCapture(service, data.getString("request_id"), data.getString("token"), format,
      service.connection.streamEndpoint(data.getString("request_id"), "audio"), ::captureEnded)
     capture = session; lastError = ""; session.requestConsent(); service.uiChanged(); return null
    }
    val playback = Playback(service.connection.streamEndpoint(data.getString("request_id"), "audio"), data.getString("token"), data.getString("request_id"), format)
    lastError = ""
    current = playback
    playback.launch()
    return null
   }
   "stop" -> {
    val id = data.optString("request_id")
    if (id.isBlank() || id == current?.requestId || id == capture?.requestId) stop()
    return JSONObject().put("state", "stopped") }
   else -> error("Unsupported audio action")
  }
 }
 @Synchronized fun stop() {
  val old = current; current = null; old?.close()
  val recording = capture; capture = null; recording?.close()
  service.refreshForeground(); service.uiChanged()
 }
 @Synchronized private fun ended(playback: Playback) { if (current === playback) { current = null; playback.close() } }
 fun settingsChanged() { if (!enabled()) stop() }

 private inner class Playback(private val endpoint: TlsEndpoint, private val token: String, val requestId: String, private val format: AudioWire.Format) {
  private val closed = AtomicBoolean(false)
  private val focusSuspended = AtomicBoolean(false)
  private val queue = ArrayBlockingQueue<AudioWire.Packet>(6)
  @Volatile private var socket: Closeable? = null
  private var track: AudioTrack? = null
  private var focus: AudioFocusRequest? = null
  private var wifi: WifiManager.WifiLock? = null
  @Volatile private var pcm: PcmPlayback? = null
  private var reader: Thread? = null
  private var writer: Thread? = null
  private var starter: Thread? = null
  private val manager = service.getSystemService(AudioManager::class.java)
  private val reportFailure = service.connection.streamFailureReporter(requestId)
  private fun failed(error: Exception) {
   synchronized(this) {
    if (closed.get()) return
    lastError = (error.message ?: "Phone audio failed").take(180)
    runCatching { reportFailure(lastError) }
   }
   ended(this)
  }
  fun launch() {
   starter = Thread({
    try { start(); service.result(requestId, true, JSONObject().put("state", "receiving")) }
    catch (e: Exception) { failed(e) }
   }, "unity-audio-connect").apply { start() }
  }
  private fun checkOpen() { check(!closed.get()) { "Audio routing cancelled" } }
  private fun start() {
   checkOpen()
   onMain { service.audioPlaybackChanged(); JSONObject() }
   val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
   val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
    .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener({ change ->
     when (change) {
      AudioManager.AUDIOFOCUS_LOSS -> failed(IllegalStateException("Another phone app took audio playback; start laptop audio again when it ends"))
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { focusSuspended.set(true); pcm?.suspended = true }
      AudioManager.AUDIOFOCUS_GAIN -> { focusSuspended.set(false); pcm?.suspended = false }
     }
    }, Handler(Looper.getMainLooper())).build()
   synchronized(this) { checkOpen(); focus = request }
   check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Phone audio is in use; try again when the call or other audio ends" }
   if (closed.get()) { manager.abandonAudioFocusRequest(request); checkOpen() }
   val minimum = AudioTrack.getMinBufferSize(format.rate, format.outputMask, AudioFormat.ENCODING_PCM_16BIT)
   check(minimum > 0) { "Phone does not support PCM playback" }
   val output = AudioTrack.Builder().setAudioAttributes(attributes)
    .setAudioFormat(AudioFormat.Builder().setSampleRate(format.rate).setChannelMask(format.outputMask)
     .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
    .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
    .setBufferSizeInBytes(maxOf(minimum, format.bytes * 4)).build()
   synchronized(this) { if (closed.get()) { output.release(); checkOpen() }; track = output }
   check(output.state == AudioTrack.STATE_INITIALIZED) { "Phone audio output unavailable" }
   output.setBufferSizeInFrames(format.frames * 4)
   val playback = PcmPlayback({ output.play() }, { output.pause() }, { output.flush() },
    { bytes, offset, size -> output.write(bytes, offset, size, AudioTrack.WRITE_BLOCKING) })
   pcm = playback; playback.suspended = focusSuspended.get()
   if (!endpoint.bluetooth) synchronized(this) {
    checkOpen()
    // Best effort: Android applies low-latency Wi-Fi policy only where allowed.
    val lock = service.applicationContext.getSystemService(WifiManager::class.java)
     .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Unity:laptop-audio")
    lock.setReferenceCounted(false)
    runCatching { lock.acquire(); wifi = lock }
   }
   val tls = endpoint.open(StreamKind.AUDIO, 15000) { opening ->
    synchronized(this) { if (closed.get()) { opening.close(); checkOpen() }; socket = opening }
   }
   synchronized(this) { if (closed.get()) { tls.close(); checkOpen() }; socket = tls }
   val input = DataInputStream(BufferedInputStream(tls.inputStream, format.bytes * 8))
   Protocol.write(DataOutputStream(tls.outputStream), JSONObject().put("type", "audio_hello").put("version", 1)
    .put("device_id", service.credentials.id).put("token", token))
   check(Protocol.read(input).getString("type") == "ready") { "Laptop audio could not start" }
   check(!closed.get() && service.connection.ready && enabled()) { "Audio routing cancelled" }
   tls.soTimeout = 5000
   synchronized(this) { checkOpen(); playing = true }; service.uiChanged()
   reader = Thread({
    try {
     var sequence = -1L
     while (!closed.get()) {
      val packet = AudioWire.read(input, sequence, format.bytes); sequence = packet.sequence
      while (!queue.offer(packet)) queue.poll()
     }
    } catch (e: Exception) {
     // On an explicit stop the laptop closes PCM and sends the control stop.
     // Give that separate link time to arrive before treating EOF as a failure.
     if (e is EOFException) Handler(Looper.getMainLooper()).postDelayed({
      failed(IllegalStateException("Laptop audio connection ended", e))
     }, 500) else failed(e)
    }
   }, "unity-audio-reader").apply { start() }
   writer = Thread({
    try {
     Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
     while (!closed.get()) {
      val packet = queue.take()
      playback.accept(packet, SystemClock.elapsedRealtime()) { !closed.get() }
     }
    } catch (e: Exception) { failed(e) }
   }, "unity-audio-playback").apply { start() }
  }
  @Synchronized fun close() {
   if (!closed.compareAndSet(false, true)) return
   runCatching { socket?.close() }
   starter?.interrupt(); reader?.interrupt(); writer?.interrupt(); queue.clear()
   runCatching { track?.pause() }
   runCatching { track?.flush() }
   runCatching { track?.release() }
   focus?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
   wifi?.let { runCatching { if (it.isHeld) it.release() } }; wifi = null
   playing = false; service.audioPlaybackChanged(); service.uiChanged()
  }
 }
}
