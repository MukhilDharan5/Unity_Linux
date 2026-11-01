package org.unityconnect.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import org.json.JSONObject

/** One remote Android session per laptop player. No audio focus, local playback or media echo. */
internal class LaptopMediaSession(private val service: CompanionService) {
 companion object { const val CHANNEL = "unity.laptop.media" }
 private val main = Handler(Looper.getMainLooper())
 private val manager = service.getSystemService(NotificationManager::class.java)
 private class Entry(val id: String, val session: MediaSession) {
  var player = JSONObject()
  var artworkKey: String? = null
  var bitmap: Bitmap? = null
  var metadataKey = ""
  var notificationKey = ""
  var providerKey = ""
  var volume: VolumeProvider? = null
  lateinit var open: PendingIntent
 }
 private val entries = linkedMapOf<String, Entry>()
 private var closed = false
 private val observer: () -> Unit = { update() }
 init {
  manager.createNotificationChannel(NotificationChannel(CHANNEL, "Laptop media controls", NotificationManager.IMPORTANCE_LOW).apply { description = "Control laptop playback from Now playing, the lock screen and Quick Settings" })
  CompanionEvents.observe(observer)
 }
 fun update() { main.removeCallbacks(render); main.post(render) }
 private val render = Runnable { if (!closed) render() }
 private fun render() {
  val list = service.features.desktopMedia.players()
  val players = (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optBoolean("active", true) }
  val live = players.map { it.getString("session") }.toSet()
  entries.keys.toList().filter { it !in live }.forEach(::remove)
  for (p in players) {
   val id = p.getString("session")
   if (!p.optBoolean("playing") && id !in entries) continue
   val entry = entries.getOrPut(id) { create(id) }; entry.player = p
   val image = p.optJSONObject("artwork")
   val artKey = image?.optString("data")
   if (entry.artworkKey != artKey) {
    entry.artworkKey = artKey
    // The framework may still render the previous metadata/notification bitmap.
    entry.bitmap = image?.let(MediaArtwork::decode)
   }
   val metadataKey = listOf(p.optString("track_id"), p.optString("title"), p.optString("artist"), p.optString("album"), p.optLong("duration").toString(), artKey).toString()
   if (entry.metadataKey != metadataKey) {
    entry.metadataKey = metadataKey
    entry.session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, p.optString("track_id"))
     .putString(MediaMetadata.METADATA_KEY_TITLE, p.optString("title").ifBlank { "Untitled media" })
     .putString(MediaMetadata.METADATA_KEY_ARTIST, p.optString("artist")).putString(MediaMetadata.METADATA_KEY_ALBUM, p.optString("album"))
     .putLong(MediaMetadata.METADATA_KEY_DURATION, p.optLong("duration"))
     .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, entry.bitmap).putBitmap(MediaMetadata.METADATA_KEY_ART, entry.bitmap)
     .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, p.optString("artist"))
     .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, "Laptop · ${p.optString("app_name")}").build())
   }
   var actions = 0L
   for ((action, flag) in listOf("play" to PlaybackState.ACTION_PLAY, "pause" to PlaybackState.ACTION_PAUSE, "next" to PlaybackState.ACTION_SKIP_TO_NEXT, "previous" to PlaybackState.ACTION_SKIP_TO_PREVIOUS, "seek" to PlaybackState.ACTION_SEEK_TO)) if (supports(p, action)) actions = actions or flag
   if (supports(p, "play") && supports(p, "pause")) actions = actions or PlaybackState.ACTION_PLAY_PAUSE
   if (supports(p, "pause")) actions = actions or PlaybackState.ACTION_STOP
   val speed = p.optDouble("speed", 1.0).let { if (it.isFinite()) it.coerceIn(0.0, 4.0).toFloat() else 1f }
   entry.session.setPlaybackState(PlaybackState.Builder().setActions(actions).setState(if (p.optBoolean("playing")) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
    p.optLong("position"), if (p.optBoolean("playing")) speed else 0f, SystemClock.elapsedRealtime()).build())
   volume(entry)
   entry.session.isActive = true
   val notificationKey = metadataKey + p.optBoolean("playing") + p.getJSONArray("actions").toString()
   if (entry.notificationKey != notificationKey) { entry.notificationKey = notificationKey; manager.notify(id, 40, notification(entry)) }
  }
 }
 private fun supports(p: JSONObject, action: String) = p.optJSONArray("actions")?.let { a -> (0 until a.length()).any { a.optString(it) == action } } == true
 private fun create(id: String): Entry {
  val session = MediaSession(service, "Unity laptop $id")
  val entry = Entry(id, session)
  session.setCallback(object : MediaSession.Callback() {
   override fun onPlay() = control(entry, "play")
   override fun onPause() = control(entry, "pause")
   override fun onStop() = control(entry, "pause")
   override fun onSkipToNext() = control(entry, "next")
   override fun onSkipToPrevious() = control(entry, "previous")
   override fun onSeekTo(pos: Long) = control(entry, "seek", pos)
  }, main)
  @Suppress("DEPRECATION") session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
  val open = PendingIntent.getActivity(service, 0, Intent(service, MediaActivity::class.java).setData(Uri.parse("unity-media://player/$id")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  session.setSessionActivity(open)
  entry.open = open
  return entry
 }
 private fun control(entry: Entry, action: String, position: Long? = null) {
  if (closed || entries[entry.id] !== entry) return
  val p = service.features.desktopMedia.players().let { list -> (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optString("session") == entry.id } } ?: return
  if (!supports(p, action)) return
  val data = JSONObject().put("session", entry.id)
  if (position != null) {
   if (p.optLong("duration") <= 0 || position !in 0..p.optLong("duration")) return
   data.put("position", position).put("track_id", p.optString("track_id"))
  }
  runCatching { service.connection.request("desktop_media", action, data) { ok, error -> if (!ok && !closed) Toast.makeText(service, error, Toast.LENGTH_SHORT).show() } }
   .onFailure { Toast.makeText(service, it.message ?: "Laptop media unavailable", Toast.LENGTH_SHORT).show() }
 }
 fun action(id: String, action: String) { main.post { entries[id]?.let { control(it, action) } } }
 private fun volume(entry: Entry) {
  val state = service.features.sound.desktopState()
  val available = state.optBoolean("available") && service.connection.desktopAvailable("sound")
  val key = "$available:${state.optString("output")}"
  if (entry.providerKey != key) {
   entry.providerKey = key
   val output = state.optString("output")
   entry.volume = object : VolumeProvider(if (available) VOLUME_CONTROL_ABSOLUTE else VOLUME_CONTROL_FIXED, 100, state.optInt("volume").coerceIn(0, 100)) {
    override fun onSetVolumeTo(volume: Int) { main.post { setVolume(entry, output, volume) } }
    override fun onAdjustVolume(direction: Int) { if (direction in listOf(-1, 1)) main.post { setVolume(entry, output, service.features.sound.desktopState().optInt("volume") + direction * 5) } }
   }.also { entry.session.setPlaybackToRemote(it) }
  }
  entry.volume?.setCurrentVolume(state.optInt("volume").coerceIn(0, 100))
 }
 private fun setVolume(entry: Entry, output: String, value: Int) {
  if (closed || entries[entry.id] !== entry || service.features.sound.desktopState().optString("output") != output) return
  runCatching { service.connection.request("sound", "volume", JSONObject().put("source", "desktop").put("output", output).put("volume", value.coerceIn(0, 100))) { ok, error -> if (!ok && !closed) Toast.makeText(service, error, Toast.LENGTH_SHORT).show() } }
 }
 private fun notification(entry: Entry): Notification {
  val p = entry.player
  val builder = Notification.Builder(service, CHANNEL).setSmallIcon(R.drawable.ic_media).setContentTitle(p.optString("title").ifBlank { "Untitled media" })
   .setContentText(p.optString("artist")).setSubText("Laptop · ${p.optString("app_name")}").setLargeIcon(entry.bitmap)
   .setCategory(Notification.CATEGORY_TRANSPORT).setVisibility(Notification.VISIBILITY_PUBLIC).setShowWhen(false).setOnlyAlertOnce(true)
   .setContentIntent(entry.open).setOngoing(p.optBoolean("playing"))
  val actions = listOf("previous" to android.R.drawable.ic_media_previous, (if (p.optBoolean("playing")) "pause" else "play") to (if (p.optBoolean("playing")) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play), "next" to android.R.drawable.ic_media_next).filter { supports(p, it.first) }
  for ((action, icon) in actions) {
   val intent = Intent(service, LaptopMediaAction::class.java).setAction(action).setData(Uri.parse("unity-media://player/${entry.id}/$action")).putExtra("session", entry.id)
   builder.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(service, icon), action.replaceFirstChar { it.uppercase() }, PendingIntent.getBroadcast(service, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
  }
  builder.setStyle(Notification.MediaStyle().setMediaSession(entry.session.sessionToken).setShowActionsInCompactView(*(actions.indices.take(3).toIntArray())))
  return builder.build()
 }
 private fun remove(id: String) { entries.remove(id)?.let { it.session.setPlaybackState(PlaybackState.Builder().setActions(0).setState(PlaybackState.STATE_STOPPED,0,0f).build());it.session.isActive = false; it.session.release(); manager.cancel(id, 40) } }
 fun clear() { main.post { entries.keys.toList().forEach(::remove) } }
 fun close() { closed = true; CompanionEvents.remove(observer); main.removeCallbacks(render); clear() }
 internal fun sessions(): Map<String, MediaSession> = entries.mapValues { it.value.session }
}

/** Notification actions are explicit immutable intents scoped to the live remote session. */
class LaptopMediaAction : BroadcastReceiver() {
 override fun onReceive(context: Context, intent: Intent) {
  val id = intent.getStringExtra("session") ?: return; val action = intent.action ?: return
  if (Protocol.validId(id) && action in listOf("play", "pause", "previous", "next")) CompanionService.instance?.features?.desktopMedia?.systemControls?.action(id, action)
 }
}
