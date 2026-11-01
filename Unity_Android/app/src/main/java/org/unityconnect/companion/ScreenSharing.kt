package org.unityconnect.companion

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.sqrt

internal data class ScreenRequest(val id: String, val token: String, val source: String, val control: Boolean = false, val blankDisplay: Boolean = false, val extendDisplay: Boolean = false) {
 companion object {
  fun parse(data: JSONObject): ScreenRequest {
   val id = data.getString("request_id"); val token = data.getString("token"); val source = data.getString("source")
   require(Protocol.validId(id) && token.length == 64 && token.all { it in "0123456789abcdef" })
   require(source in listOf("phone", "desktop") && data.getString("format") == "jpeg")
   require(data.getInt("max_edge") == 1280 && data.getInt("max_pixels") == 921600 && data.getInt("max_bytes") == 262144 && data.getInt("fps") == 15)
   for (key in listOf("control", "blank_display", "extend_display")) if (data.has(key)) require(data.get(key) is Boolean) { "Invalid display option" }
   val blank = data.optBoolean("blank_display", false); val extend = data.optBoolean("extend_display", false)
   require(!(blank && extend) && (source == "desktop" || (!blank && !extend))) { "Choose a desktop primary or extended display" }
   return ScreenRequest(id, token, source, data.optBoolean("control", false), blank, extend)
  }
 }
}
internal fun screenDimensions(width: Int, height: Int): Pair<Int, Int> {
 require(width > 0 && height > 0)
 val ratio = min(1.0, min(1280.0 / maxOf(width, height), sqrt(921600.0 / (width.toDouble() * height))))
 return maxOf(1, (width * ratio).toInt()) to maxOf(1, (height * ratio).toInt())
}
internal fun decodeScreen(bytes: ByteArray): Bitmap {
 require(bytes.size in 4..262144) { "Screen frame exceeds byte limit" }
 val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
 BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
 require(options.outMimeType == "image/jpeg" && options.outWidth > 0 && options.outHeight > 0 &&
  maxOf(options.outWidth, options.outHeight) <= 1280 && options.outWidth.toLong() * options.outHeight <= 921600) { "Invalid screen dimensions" }
 return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Invalid screen image" }
}

/** Pack RGBA rows without assuming padding exists after the final image row. */
internal fun screenPixels(buffer: ByteBuffer, rowStride: Int, width: Int, height: Int, reusable: ByteBuffer? = null): ByteBuffer {
 require(width in 1..1280 && height in 1..1280 && width.toLong() * height <= 921600)
 val rowBytes = width * 4
 require(rowStride >= rowBytes && buffer.limit().toLong() >= (height - 1L) * rowStride + rowBytes) { "Truncated screen pixels" }
 val source = buffer.duplicate()
 if (rowStride == rowBytes) { source.position(0); source.limit(rowBytes * height); return source }
 val packed = reusable?.takeIf { it.capacity() == rowBytes * height } ?: ByteBuffer.allocate(rowBytes * height)
 packed.clear()
 for (row in 0 until height) {
  source.limit(buffer.limit()); source.position(row * rowStride)
  source.limit(row * rowStride + rowBytes); packed.put(source)
 }
 packed.flip()
 return packed
}

/** One consent, one projection, one latest frame. Never persist screen pixels. */
class ScreenSharing(service: CompanionService) : Module(service, "screen") {
 private val main = Handler(Looper.getMainLooper())
 @Volatile private var current: Session? = null
 @Volatile var bitmap: Bitmap? = null
  private set
 @Volatile var state = "stopped"
  private set
 @Volatile var lastError = ""
  private set
 @Volatile var projectionForeground = false
  private set
 @Volatile private var consentOpen = false
 @Volatile private var viewerOpen = false
 @Volatile var inputEnabled = false
  private set
 @Volatile var displayAvailable = false
  private set
 @Volatile var displayOn = true
  private set
 @Volatile var phonePrimary = false
  private set
 @Volatile var extendedDisplay = false
  private set
 @Volatile var controlError = ""
  private set
 fun active() = current != null
 fun source() = current?.request?.source
 fun currentId() = current?.request?.id
 fun pendingId() = current?.takeIf { it.request.source == "phone" && !it.started.get() }?.request?.id
 override fun capability() = status(reason = "Each screen capture requires approval on the device sharing its screen")
 fun settingsChanged() { if (!enabled() && active()) stop() }
 override fun command(action: String, data: JSONObject): JSONObject? {
  if (action == "state") {
   if (current?.request?.id == data.optString("request_id") && source() == "desktop") {
    inputEnabled = data.optBoolean("input_enabled"); displayAvailable = data.optBoolean("display_available")
    phonePrimary = data.optBoolean("phone_primary", false)
    extendedDisplay = data.optBoolean("extended_display", false)
    displayOn = data.optBoolean("display_on", true); controlError = data.optString("display_error").take(512)
    main.post { maybeBlank(); service.uiChanged() }
   }
   return JSONObject().put("state", "received")
  }
  if (action == "stop") { stop(data.optString("error")); return JSONObject().put("state", "stopped") }
  require(action == "start"); check(enabled()) { "Enable screen sharing on your phone" }
  val request = ScreenRequest.parse(data)
  val session = synchronized(this) {
   check(current == null) { "Stop the current screen share first" }
   Session(request, service.connection.streamEndpoint(request.id, "screen")).also { current = it; bitmap = null; state = "starting"; lastError = "" }
  }
  if (request.source == "phone") {
   notifyAction("Approve phone screen sharing", ScreenConsentActivity::class.java, request.id)
   main.postDelayed({ if (current === session && !session.started.get()) fail(session, "Screen capture approval timed out") }, 115000)
  } else {
   notifyAction("View your computer screen", ScreenViewerActivity::class.java, request.id)
   session.connect()
  }
  service.uiChanged()
  return null
 }
 private fun notifyAction(title: String, activity: Class<out Activity>, id: String) {
  val intent = Intent(service, activity).putExtra("screen_request", id)
  val pending = PendingIntent.getActivity(service, 730, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  service.getSystemService(NotificationManager::class.java).notify(730, Notification.Builder(service, "unity.actions")
   .setSmallIcon(R.drawable.ic_computer).setContentTitle(title).setContentText("Tap to open Unity")
   .setContentIntent(pending).setAutoCancel(true).build())
 }
 fun presentPending(activity: Activity) {
  val session = current ?: return
  if (session.request.source == "phone" && !session.started.get() && !consentOpen) {
   consentOpen = true
   activity.startActivity(Intent(activity, ScreenConsentActivity::class.java).putExtra("screen_request", session.request.id))
  } else if (session.request.source == "desktop" && !viewerOpen) {
   viewerOpen = true
   activity.startActivity(Intent(activity, ScreenViewerActivity::class.java).putExtra("screen_request", session.request.id))
  }
 }
 fun consent(id: String?, result: Int, data: Intent?) {
  consentOpen = false
  val session = current?.takeIf { it.request.id == id && it.request.source == "phone" && !it.started.get() } ?: return
  if (result != Activity.RESULT_OK || data == null) { fail(session, "Screen capture permission was denied"); return }
  try {
   check(enabled() && service.connection.ready) { "Screen sharing is no longer enabled or connected" }
   session.started.set(true)
   // Android requires this foreground type before obtaining the approved projection.
   projectionForeground = true; service.refreshForeground()
   session.capture(result, data)
   session.connect()
  } catch (e: Exception) { fail(session, e.message ?: "Phone screen capture failed") }
 }
 fun viewerOpened() { viewerOpen = true; main.post { maybeBlank() } }
 fun viewerVisibility(id: String?, viewer: Any, visible: Boolean) {
  val session = current?.takeIf { it.request.id == id && it.request.source == "desktop" } ?: return
  if (visible) session.visibleViewers.add(viewer) else session.visibleViewers.remove(viewer)
  if (visible) maybeBlank()
 }
 fun canControl() = active() && source() == "desktop" && state == "streaming" && inputEnabled && service.connection.ready
 fun input(data: JSONObject) {
  if (Looper.myLooper() != Looper.getMainLooper()) { main.post { input(data) }; return }
  val session = current ?: return
  if (!canControl()) return
  val inputQueue = session.inputQueue
  val kind = data.optString("kind")
  if (kind == "motion" && inputQueue.peekLast()?.optString("kind") == "motion") inputQueue.removeLast()
  if (inputQueue.size >= 64) { stopRemote("Remote input is too slow; reconnect and try again"); return }
  data.put("session_id", session.request.id); inputQueue.addLast(data)
  drainInput(session)
 }
 private fun drainInput(session: Session) {
  if (current !== session || !canControl() || session.inputSending || session.inputQueue.isEmpty()) return
  val data = session.inputQueue.removeFirst().put("seq", ++session.inputSequence)
  session.inputSending = true
  try {
   service.connection.request("screen", "input", data) { ok, error ->
    if (current !== session) return@request
    session.inputSending = false
    if (!ok) { stopRemote(error.ifBlank { "Remote input stopped" }); return@request }
    drainInput(session)
   }
  } catch (error: Exception) { session.inputSending = false; stopRemote(error.message ?: "Remote input connection lost") }
 }
 fun setDisplayOn(on: Boolean) {
  if (Looper.myLooper() != Looper.getMainLooper()) { main.post { setDisplayOn(on) }; return }
  val session = current ?: return
  if (source() != "desktop" || state != "streaming" || (!on && (!canControl() || !displayAvailable))) return
  try {
   service.connection.request("screen", "display", JSONObject().put("session_id", session.request.id).put("on", on)) { ok, error ->
    if (current !== session) return@request
    if (ok) { displayOn = on; controlError = "" } else controlError = error.ifBlank { "Laptop display control failed" }
    service.uiChanged()
   }
  } catch (error: Exception) { controlError = (error.message ?: "Display connection lost").take(512); service.uiChanged() }
 }
 private fun maybeBlank() {
  val session = current ?: return
  if (viewerOpen && session.visibleViewers.isNotEmpty() && canControl() && bitmap != null && displayAvailable && session.request.blankDisplay && !session.blankAttempted) {
   session.blankAttempted = true; setDisplayOn(false)
  }
 }
 fun stop(error: String = "") {
  val session = synchronized(this) { current.also { current = null; bitmap = null; state = if (error.isBlank()) "stopped" else "failed"; lastError = error.take(512) } }
  session?.close()
  main.post { session?.inputQueue?.clear(); session?.inputSending = false }
  inputEnabled = false; displayAvailable = false; displayOn = true; phonePrimary = false; extendedDisplay = false; controlError = ""
  consentOpen = false; viewerOpen = false; projectionForeground = false
  service.getSystemService(NotificationManager::class.java).cancel(730)
  service.refreshForeground(); service.uiChanged()
 }
 fun stopRemote(error: String = "") {
  stop(error)
  if (service.connection.ready && service.connection.desktopAvailable("screen")) {
   runCatching { service.connection.request("screen", "stop") { _, _ -> } }
  }
 }
 private fun fail(session: Session, error: String) {
  if (current !== session) return
  runCatching { service.result(session.request.id, false, error = error) }
  stop(error)
  // Stop also reaches the desktop after the start operation has already completed.
  if (service.connection.ready && service.connection.desktopAvailable("screen")) {
   runCatching { service.connection.request("screen", "stop") { _, _ -> } }
  }
 }
 private inner class Session(val request: ScreenRequest, val endpoint: TlsEndpoint) {
  var inputSequence = 0L
  var blankAttempted = false
  val inputQueue = java.util.ArrayDeque<JSONObject>()
  var inputSending = false
  val visibleViewers = mutableSetOf<Any>()
  val started = AtomicBoolean(false)
  private val running = AtomicBoolean(true)
  private val frames = ArrayBlockingQueue<ByteArray>(1)
  @Volatile private var socket: Closeable? = null
  private var projection: MediaProjection? = null
  private var display: VirtualDisplay? = null
  private var images: ImageReader? = null
  private var captureThread: HandlerThread? = null
  private var packedPixels: ByteBuffer? = null
  private var width = 0; private var height = 0; private var lastFrameAt = 0L
  private val displays = service.getSystemService(DisplayManager::class.java)
  private val displayListener = object : DisplayManager.DisplayListener {
   override fun onDisplayAdded(id: Int) = Unit
   override fun onDisplayRemoved(id: Int) = Unit
   override fun onDisplayChanged(id: Int) { if (Build.VERSION.SDK_INT < 34 && running.get()) { val size = physicalSize(); resize(size.first, size.second) } }
  }
  @Suppress("DEPRECATION")
  private fun physicalSize(): Pair<Int, Int> {
   if (Build.VERSION.SDK_INT >= 30) {
    val bounds = service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
    return bounds.width() to bounds.height()
   }
   val metrics = android.util.DisplayMetrics(); displays.getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
   return metrics.widthPixels to metrics.heightPixels
  }
  @Synchronized fun capture(result: Int, intent: Intent) {
   check(running.get()) { "Screen request was cancelled" }
   val thread = HandlerThread("unity-screen-capture", Process.THREAD_PRIORITY_DISPLAY).apply { start() }; captureThread = thread
   val handler = Handler(thread.looper)
   val manager = service.getSystemService(MediaProjectionManager::class.java)
   val mp = checkNotNull(manager.getMediaProjection(result, intent)) { "Screen capture permission unavailable" }; projection = mp
   mp.registerCallback(object : MediaProjection.Callback() {
    override fun onStop() { if (running.get()) fail(this@Session, "Phone screen capture ended") }
    override fun onCapturedContentResize(w: Int, h: Int) { if (running.get()) resize(w, h) }
   }, handler)
   val physical = physicalSize(); val size = screenDimensions(physical.first, physical.second)
   width = size.first; height = size.second
   images = imageReader(width, height, handler)
   display = mp.createVirtualDisplay("Unity screen", width, height, service.resources.displayMetrics.densityDpi,
    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, images!!.surface, null, handler)
   if (Build.VERSION.SDK_INT < 34) displays.registerDisplayListener(displayListener, handler)
  }
  @Synchronized private fun resize(w: Int, h: Int) {
   if (!running.get() || display == null) return
   runCatching {
    val size = screenDimensions(w, h)
    if (size.first == width && size.second == height) return
    val next = imageReader(size.first, size.second, Handler(checkNotNull(captureThread).looper))
    display!!.resize(size.first, size.second, service.resources.displayMetrics.densityDpi)
    display!!.surface = next.surface
    val previous = images; images = next; width = size.first; height = size.second; previous?.close()
   }.onFailure { fail(this, it.message ?: "Screen resize failed") }
  }
  private fun imageReader(w: Int, h: Int, handler: Handler): ImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).apply {
   setOnImageAvailableListener({ reader ->
    runCatching {
     reader.acquireLatestImage()?.use { image ->
      if (!running.get() || SystemClock.elapsedRealtime() - lastFrameAt < 66) return@use
      lastFrameAt = SystemClock.elapsedRealtime()
      val plane = image.planes[0]
      require(plane.pixelStride == 4) { "Unsupported screen pixel layout" }
      val cropped = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
      try {
       val pixels = screenPixels(plane.buffer, plane.rowStride, image.width, image.height, packedPixels)
       if (plane.rowStride != image.width * 4) packedPixels = pixels
       cropped.copyPixelsFromBuffer(pixels)
       val bytes = ByteArrayOutputStream()
       for (quality in intArrayOf(70, 50, 30, 15)) {
        bytes.reset(); cropped.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
        if (bytes.size() <= 262144) break
       }
       if (running.get() && bytes.size() <= 262144) { frames.poll(); frames.offer(bytes.toByteArray()) }
      } finally { cropped.recycle() }
     }
    }.onFailure { if (running.get()) fail(this@Session, it.message ?: "Screen encoding failed") }
   }, handler)
  }
  fun connect() {
   Thread({
    try {
     check(running.get())
     val tls = endpoint.open(StreamKind.SCREEN, 150000) { opening -> socket = opening; if (!running.get()) { opening.close(); error("Screen sharing cancelled") } }
     socket = tls
     if (!running.get()) { tls.close(); error("Screen sharing cancelled") }
     val input = DataInputStream(tls.inputStream); val output = DataOutputStream(tls.outputStream)
     Protocol.write(output, JSONObject().put("type", "screen_hello").put("version", 1).put("device_id", service.credentials.id).put("token", request.token))
     require(Protocol.read(input).getString("type") == "ready")
     tls.soTimeout = 15000
     if (request.source == "phone") {
      while (running.get()) {
       val bytes = frames.poll(15, TimeUnit.SECONDS) ?: error("Phone screen capture stalled")
       val stalled = Runnable { if (running.get()) { runCatching { tls.close() } } }
       main.postDelayed(stalled, 1500)
       try { output.writeInt(bytes.size); output.write(bytes); output.flush() } finally { main.removeCallbacks(stalled) }
       if (current === this && state != "streaming") { state = "streaming"; service.uiChanged() }
      }
     } else {
      while (running.get()) {
       val length = input.readInt(); require(length in 4..262144) { "Screen frame exceeds byte limit" }
       val bytes = ByteArray(length); input.readFully(bytes)
       val frame = decodeScreen(bytes)
       if (current === this && running.get()) { bitmap = frame; state = "streaming"; main.post { maybeBlank(); service.uiChanged() } }
      }
     }
    } catch (e: Exception) { if (running.get()) fail(this, e.message ?: "Screen connection lost") }
    finally { runCatching { socket?.close() }; close() }
   }, "unity-screen-stream").start()
  }
  @Synchronized fun close() {
   if (!running.compareAndSet(true, false)) return
   runCatching { socket?.close() }; frames.clear(); packedPixels = null
   runCatching { displays.unregisterDisplayListener(displayListener) }
   runCatching { display?.release() }; display = null
   runCatching { images?.close() }; images = null
   runCatching { projection?.stop() }; projection = null
   captureThread?.quitSafely(); captureThread = null
  }
 }
}
