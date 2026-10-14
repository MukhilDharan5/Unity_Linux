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
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed 10 ms PCM packets; no JSON or file chunks on the audio path after Ready. */
internal object AudioWire {
 const val RATE = 48000
 const val CHANNELS = 2
 const val FRAMES = 480
 const val BYTES = FRAMES * CHANNELS * 2
 fun validate(data: JSONObject) {
  require(data.getInt("rate") == RATE && data.getInt("channels") == CHANNELS &&
   data.getInt("frames") == FRAMES && data.getString("format") == "s16le") { "Unsupported audio format" }
  val token = data.getString("token")
  require(token.length == 64 && Protocol.unhex(token).size == 32) { "Invalid audio token" }
  require(Protocol.validId(data.getString("request_id"))) { "Invalid audio request" }
 }
 data class Packet(val sequence: Long, val pcm: ByteArray, val received: Long)
 fun read(input: DataInputStream, previous: Long): Packet {
  val sequence = input.readLong()
  require(sequence >= 0 && sequence > previous) { "Invalid audio packet sequence" }
  val pcm = ByteArray(BYTES); input.readFully(pcm)
  return Packet(sequence, pcm, SystemClock.elapsedRealtime())
 }
}

class AudioRouting(service: CompanionService) : Module(service, "audio") {
 @Volatile private var current: Playback? = null
 fun active() = current != null
 @Volatile var playing = false
  private set
 @Volatile var lastError = ""
  private set
 @Synchronized override fun command(action: String, data: JSONObject): JSONObject? {
  when (action) {
   "start" -> {
    AudioWire.validate(data)
    check(enabled()) { "Enable laptop audio in Features" }
    check(current == null) { "Laptop audio is already playing" }
    val playback = Playback(service.connection.streamEndpoint(data.getString("request_id"), "audio"), data.getString("token"), data.getString("request_id"))
    lastError = ""
    current = playback
    playback.launch()
    return null
   }
   "stop" -> { stop(); return JSONObject().put("state", "stopped") }
   else -> error("Unsupported audio action")
  }
 }
 @Synchronized fun stop() { val old = current; current = null; old?.close() }
 @Synchronized private fun ended(playback: Playback) { if (current === playback) { current = null; playback.close() } }
 fun settingsChanged() { if (!enabled()) stop() }

 private inner class Playback(private val endpoint: TlsEndpoint, private val token: String, private val requestId: String) {
  private val closed = AtomicBoolean(false)
  private val queue = ArrayBlockingQueue<AudioWire.Packet>(3)
  @Volatile private var socket: Closeable? = null
  private var track: AudioTrack? = null
  private var focus: AudioFocusRequest? = null
  private var reader: Thread? = null
  private var writer: Thread? = null
  private var starter: Thread? = null
  private val manager = service.getSystemService(AudioManager::class.java)
  fun launch() {
   starter = Thread({
    try { start(); service.result(requestId, true, JSONObject().put("state", "receiving")) }
    catch (e: Exception) {
     if (!closed.get()) {
      lastError = (e.message ?: "Phone audio failed").take(180)
      runCatching { service.result(requestId, false, error = lastError) }
     }
     ended(this)
    }
   }, "unity-audio-connect").apply { start() }
  }
  private fun checkOpen() { check(!closed.get()) { "Audio routing cancelled" } }
  private fun start() {
   checkOpen()
   service.audioPlaybackChanged()
   val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
   val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
    .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener({ change ->
     if (change < 0) ended(this)
    }, Handler(Looper.getMainLooper())).build()
   synchronized(this) { checkOpen(); focus = request }
   check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Phone audio is in use; try again when the call or other audio ends" }
   if (closed.get()) { manager.abandonAudioFocusRequest(request); checkOpen() }
   val minimum = AudioTrack.getMinBufferSize(AudioWire.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
   check(minimum > 0) { "Phone does not support PCM playback" }
   val output = AudioTrack.Builder().setAudioAttributes(attributes)
    .setAudioFormat(AudioFormat.Builder().setSampleRate(AudioWire.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
     .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
    .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
    .setBufferSizeInBytes(maxOf(minimum, AudioWire.BYTES * 2)).build()
   synchronized(this) { if (closed.get()) { output.release(); checkOpen() }; track = output }
   check(output.state == AudioTrack.STATE_INITIALIZED) { "Phone audio output unavailable" }
   output.setBufferSizeInFrames(AudioWire.FRAMES * 2)
   val tls = endpoint.open(StreamKind.AUDIO, 15000) { opening ->
    synchronized(this) { if (closed.get()) { opening.close(); checkOpen() }; socket = opening }
   }
   synchronized(this) { if (closed.get()) { tls.close(); checkOpen() }; socket = tls }
   val input = DataInputStream(tls.inputStream)
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
      val packet = AudioWire.read(input, sequence); sequence = packet.sequence
      while (!queue.offer(packet)) queue.poll()
     }
    } catch (_: Exception) { ended(this) }
   }, "unity-audio-reader").apply { start() }
   writer = Thread({
    try {
     Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
     var sequence = -1L
     var started = false
     while (!closed.get()) {
      val packet = queue.take()
      if (SystemClock.elapsedRealtime() - packet.received > 50) continue
      if (sequence >= 0 && packet.sequence != sequence + 1) {
       output.pause(); output.flush(); started = false
      }
      sequence = packet.sequence
      var offset = 0
      while (offset < packet.pcm.size && !closed.get()) {
       val written = output.write(packet.pcm, offset, packet.pcm.size - offset, AudioTrack.WRITE_BLOCKING)
       check(written > 0) { "Phone audio playback failed" }; offset += written
      }
      if (!started && !closed.get()) { output.play(); started = true }
     }
    } catch (_: Exception) { ended(this) }
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
   playing = false; service.audioPlaybackChanged(); service.uiChanged()
  }
 }
}
