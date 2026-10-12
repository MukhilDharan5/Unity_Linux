package org.unityconnect.companion

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.*
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.Executors

class MediaFeature(service: CompanionService) : Module(service, "media") {
 private val handler = Handler(Looper.getMainLooper())
 private val watched = mutableListOf<MediaController>()
 private val manager = service.getSystemService(MediaSessionManager::class.java)
 private val component = ComponentName(service, PhoneNotifications::class.java)
 private var observing = false
 private val images = Executors.newSingleThreadExecutor()
 private var cachedBitmap: android.graphics.Bitmap? = null
 private var cachedArt: JSONObject? = null
 private var cachedUri: String? = null
 private val callback = object : MediaController.Callback() {
  override fun onMetadataChanged(metadata: MediaMetadata?) { publish() }
  override fun onPlaybackStateChanged(state: PlaybackState?) { publish() }
  override fun onSessionDestroyed() { watch() }
 }
 private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { watch() }
 override fun capability() = status(PhoneNotifications.instance != null, "Enable notification listener access for MediaSession controls")
 private fun sessions(): List<MediaController> = runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList())
 fun watch() {
  handler.post {
   stopNow()
   if (!service.connection.ready || capability().getString("status") != "available") return@post
   runCatching { manager.addOnActiveSessionsChangedListener(sessionListener, component, handler); observing = true }
   watched.addAll(sessions()); watched.forEach { it.registerCallback(callback, handler) }; publish()
  }
 }
 private fun stopNow() { watched.forEach { it.unregisterCallback(callback) }; watched.clear(); if (observing) manager.removeOnActiveSessionsChangedListener(sessionListener); observing = false }
 fun stop() { handler.post { stopNow() } }
 fun close() { stop(); images.shutdownNow() }
 private fun publish() {
  if (!service.connection.ready) return
  val controller = watched.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: watched.firstOrNull()
  val metadata = controller?.metadata
  val playback = controller?.playbackState
  val supported = JSONArray()
  for ((name, flag) in listOf("play" to PlaybackState.ACTION_PLAY, "pause" to PlaybackState.ACTION_PAUSE,
   "previous" to PlaybackState.ACTION_SKIP_TO_PREVIOUS, "next" to PlaybackState.ACTION_SKIP_TO_NEXT, "seek" to PlaybackState.ACTION_SEEK_TO)) {
   if (((playback?.actions ?: 0L) and flag) != 0L) supported.put(name)
  }
  val app = controller?.packageName ?: ""
  val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
   ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
  val uri = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
   ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
  val value = JSONObject().put("title", Protocol.text(metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "", 512))
   .put("artist", Protocol.text(metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "", 512)).put("playing", controller?.playbackState?.state == PlaybackState.STATE_PLAYING)
   .put("album", Protocol.text(metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "", 512))
   .put("duration", (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0).coerceIn(0, 86400000))
   .put("position", (playback?.position ?: 0).coerceIn(0, 86400000)).put("package", app).put("active", controller != null)
   .put("app_name", Protocol.text(runCatching { service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(app, 0)).toString() }.getOrDefault(app), 256))
   .put("actions", supported)
  runCatching { images.execute {
   if (bitmap !== cachedBitmap || uri != cachedUri) {
    cachedArt = bitmap?.let { runCatching { ImagePreview.encode(it) }.getOrNull() }
    if (cachedArt == null && uri != null) {
     val local = android.net.Uri.parse(uri)
     if (local.scheme == "content") runCatching {
      val thumbnail = service.contentResolver.loadThumbnail(local, android.util.Size(256, 256), null)
      try { cachedArt = ImagePreview.encode(thumbnail) } finally { thumbnail.recycle() }
     }
    }
    cachedBitmap = bitmap; cachedUri = uri
   }
   value.put("artwork", cachedArt ?: JSONObject.NULL)
   runCatching { service.event(id, value) }
  } }
 }
 override fun command(action: String, data: JSONObject): JSONObject = onMain {
  if (action == "refresh") { publish(); return@onMain JSONObject().put("state", "refreshed") }
  val controller = sessions().firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions().firstOrNull() ?: error("No active media session")
  val controls = controller.transportControls
  val required = when (action) { "play" -> PlaybackState.ACTION_PLAY; "pause" -> PlaybackState.ACTION_PAUSE; "next" -> PlaybackState.ACTION_SKIP_TO_NEXT; "previous" -> PlaybackState.ACTION_SKIP_TO_PREVIOUS; "seek" -> PlaybackState.ACTION_SEEK_TO; "refresh" -> 0L; else -> error("Unsupported action") }
  check(required == 0L || ((controller.playbackState?.actions ?: 0L) and required) != 0L) { "Media app does not support this control" }
  when (action) { "play" -> controls.play(); "pause" -> controls.pause(); "next" -> controls.skipToNext(); "previous" -> controls.skipToPrevious(); "seek" -> { val p = data.getLong("position"); require(p in 0..86400000); controls.seekTo(p) }; "refresh" -> publish() }
  JSONObject().put("state", "submitted_to_media_session")
 }
}
