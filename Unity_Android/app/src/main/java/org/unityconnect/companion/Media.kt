package org.unityconnect.companion

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

/** Only native phone sessions are published; Unity remote sessions are excluded from discovery. */
class MediaFeature(service: CompanionService) : Module(service, "media") {
 private val handler = Handler(Looper.getMainLooper())
 private val watched = mutableListOf<MediaController>()
 private val ids = mutableMapOf<MediaSession.Token, String>()
 private val manager = service.getSystemService(MediaSessionManager::class.java)
 private val component = ComponentName(service, PhoneNotifications::class.java)
 private var observing = false
 private var epoch = 0
 private val images = java.util.concurrent.ThreadPoolExecutor(2,2,0L,java.util.concurrent.TimeUnit.SECONDS,java.util.concurrent.ArrayBlockingQueue(16))
 private var lastWire = ""
 private val sentArtwork = mutableMapOf<String, String>()
 private var artworkRoute: Any? = null
 private data class Art(val key: String, var encoded: JSONObject?, var checked: Long, var generation: Int, var loading: Boolean)
 private val artCache=mutableMapOf<String,Art>()
 private val previous=mutableMapOf<String,JSONObject>()
 @Volatile private var snapshot = "[]"
 @Volatile private var covers: Map<String,JSONObject> = emptyMap()
 fun players(): JSONArray = if (capability().optString("status")=="available") JSONArray(snapshot).apply {
  val images=covers
  for (i in 0 until length()) { val p=getJSONObject(i);images[p.optString("artwork_id")]?.let {p.put("artwork",it)} }
 } else JSONArray()
 private val callback = object : MediaController.Callback() {
  override fun onMetadataChanged(metadata: MediaMetadata?) { publish() }
  override fun onPlaybackStateChanged(state: PlaybackState?) { publish() }
  override fun onSessionDestroyed() { watch() }
 }
 private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { watch() }
 private val tick = object : Runnable { override fun run() { if (observing) { publish(); handler.postDelayed(this,1000) } } }
 override fun capability() = status(PhoneNotifications.instance != null, "Enable notification listener access for phone MediaSession controls")
 private fun sessions(): List<MediaController> = if (capability().getString("status") == "available")
  runCatching { manager.getActiveSessions(component).filter { it.packageName != service.packageName }.take(16) }.getOrDefault(emptyList()) else emptyList()
 private fun sessionId(controller: MediaController): String = ids.getOrPut(controller.sessionToken) { UUID.randomUUID().toString().replace("-", "") }
 fun watch() { handler.post {
  stopNow()
  if (capability().getString("status") != "available") { snapshot="[]"; covers=emptyMap(); artCache.clear(); previous.clear(); service.uiChanged(); return@post }
  runCatching { manager.addOnActiveSessionsChangedListener(sessionListener, component, handler); observing = true }
  watched.addAll(sessions()); ids.keys.retainAll(watched.map { it.sessionToken }.toSet())
  watched.forEach { it.registerCallback(callback, handler) }; publish(); handler.postDelayed(tick,1000)
 } }
 private fun stopNow() {
  epoch++; lastWire=""; sentArtwork.clear(); artworkRoute=null; handler.removeCallbacks(tick)
  watched.forEach { it.unregisterCallback(callback) }; watched.clear()
  if (observing) manager.removeOnActiveSessionsChangedListener(sessionListener); observing = false
 }
 fun stop() { handler.post { stopNow(); snapshot="[]"; covers=emptyMap(); artCache.clear(); previous.clear(); service.uiChanged() } }
 fun close() { stop(); images.shutdownNow() }
 internal fun remoteCapabilitiesChanged() {
  val supported=service.connection.desktopAvailable("media_artwork")
  if (!supported) handler.post { sentArtwork.clear() }
 }
 private fun publish() {
  if (capability().getString("status") != "available") return
  val controllers=watched.sortedByDescending { it.playbackState?.state == PlaybackState.STATE_PLAYING }
  val list=JSONArray(); val live=mutableSetOf<String>()
  for (controller in controllers) {
   val metadata=controller.metadata; val playback=controller.playbackState
   val id=sessionId(controller); live.add(id)
   val title=Protocol.text((metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf {it.isNotBlank()} ?: metadata?.description?.title?.toString()).orEmpty().replace("\u0000",""),512)
   val artist=Protocol.text((metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf {it.isNotBlank()} ?: metadata?.description?.subtitle?.toString()).orEmpty().replace("\u0000",""),512)
   val album=Protocol.text(metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),512)
   val track=Protocol.text(metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
    ?: metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI) ?: "$title:$artist:$album",1024)
   val old=previous[id]; val same=old?.optString("track_id")==track
   var duration=(metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0).coerceIn(0,86400000)
   if (duration==0L && same) duration=old?.optLong("duration") ?: 0L
   val supported=JSONArray()
   for ((name,flag) in listOf("play" to PlaybackState.ACTION_PLAY,"pause" to PlaybackState.ACTION_PAUSE,"previous" to PlaybackState.ACTION_SKIP_TO_PREVIOUS,"next" to PlaybackState.ACTION_SKIP_TO_NEXT,"seek" to PlaybackState.ACTION_SEEK_TO)) {
    if (((playback?.actions ?: 0L) and flag) != 0L) supported.put(name)
   }
   val playing=playback?.state == PlaybackState.STATE_PLAYING
   val speed=(playback?.playbackSpeed ?: 1f).let {if(it.isFinite())it.coerceIn(0f,4f)else 1f}
   val advanced=if (playing && playback.lastPositionUpdateTime>0) ((SystemClock.elapsedRealtime()-playback.lastPositionUpdateTime).coerceAtLeast(0)*speed).toLong() else 0L
   val app=controller.packageName
   val value=JSONObject().put("origin","phone").put("session",id).put("track_id",track).put("active",true).put("package",app)
    .put("app_name",Protocol.text(runCatching { service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(app,0)).toString() }.getOrDefault(app),256))
    .put("title",title).put("artist",artist).put("album",album).put("duration",duration)
    .put("position",((playback?.position ?: 0L)+advanced).coerceIn(0,if (duration>0) duration else 86400000))
    .put("playing",playing).put("speed",speed).put("actions",supported)
   val bitmap=listOfNotNull(metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART), metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART), metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON), metadata?.description?.iconBitmap).maxByOrNull { it.width.toLong()*it.height }
   val uris=listOfNotNull(metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI),
    metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),metadata?.description?.iconUri?.toString()).filter {it.isNotBlank() && it.length<=8192}.distinct()
   val key="$track:${System.identityHashCode(bitmap)}:${uris.joinToString("|")}"
   val cached=artCache[id]?.takeIf { it.key==key } ?: Art(key,null,0,epoch,false).also {artCache[id]=it}
   val now=SystemClock.elapsedRealtime()
   val retrySoon=cached.encoded==null || (uris.isNotEmpty() && maxOf(cached.encoded!!.optInt("width"),cached.encoded!!.optInt("height"))<MediaArtwork.MAX_EDGE)
   if ((!cached.loading || cached.generation!=epoch) && (cached.checked==0L || now-cached.checked>=if(retrySoon)3000 else 60000)) {
    cached.loading=true;cached.generation=epoch;val generation=epoch
    try { images.execute {
     val artwork=runCatching { loadArtwork(controller,bitmap,uris) }.getOrNull()
     handler.post {
      if (generation!=epoch || artCache[id]?.key!=key) return@post
      artCache[id]?.apply { loading=false;checked=SystemClock.elapsedRealtime().coerceAtLeast(1);if(artwork!=null && (encoded==null || artwork.optInt("width").toLong()*artwork.optInt("height")>=encoded!!.optInt("width").toLong()*encoded!!.optInt("height")))encoded=artwork }
      if (observing) publish()
     }
    } } catch (_: Exception) { cached.loading=false;cached.checked=now.coerceAtLeast(1) }
   }
   value.put("artwork",cached.encoded ?: JSONObject.NULL)
   cached.encoded?.optString("id")?.takeIf { it.isNotEmpty() }?.let { value.put("artwork_id",it) }
   previous[id]=MediaArtwork.wire(value);list.put(value)
  }
  artCache.keys.retainAll(live);previous.keys.retainAll(live)
  val wireList=JSONArray((0 until list.length()).map { MediaArtwork.wire(list.getJSONObject(it)) })
  covers=(0 until list.length()).mapNotNull { list.getJSONObject(it).optJSONObject("artwork")?.let {image->image.getString("id") to image} }.toMap()
  val current=wireList.toString()
  if (current != snapshot) { snapshot=current;service.uiChanged() }
  val primary=if(wireList.length()>0)JSONObject(wireList.getJSONObject(0).toString()) else JSONObject().put("origin","phone").put("title","").put("artist","").put("playing",false).put("active",false).put("actions",JSONArray())
  primary.put("players",wireList)
  if(primary.toString().toByteArray().size>60000) { for(i in wireList.length()-1 downTo 0) {wireList.getJSONObject(i).put("artwork",JSONObject.NULL);if(i==0)primary.put("artwork",JSONObject.NULL);if(primary.toString().toByteArray().size<=60000)break} }
  val wire=primary.toString()
  val metadataSent=service.connection.ready && (wire==lastWire || runCatching {service.event(id,primary);lastWire=wire;true}.getOrDefault(false))
  sentArtwork.keys.retainAll(live)
  val route=service.connection.artworkRoute();if (artworkRoute!==route) {artworkRoute=route;sentArtwork.clear()}
  if (metadataSent && service.connection.desktopAvailable("media_artwork")) for (i in 0 until list.length()) {
   val player=list.getJSONObject(i);val image=player.optJSONObject("artwork") ?: continue
   val session=player.getString("session");val key=player.optString("track_id")+":"+player.optString("artwork_id")
   if (sentArtwork[session]!=key && runCatching { service.event(id,JSONObject().put("kind","artwork").put("origin","phone").put("session",session).put("track_id",player.optString("track_id")).put("artwork_id",player.getString("artwork_id")).put("artwork",image));true }.getOrDefault(false)) sentArtwork[session]=key
  }
 }
 private fun artworkBytes(input: java.io.InputStream): ByteArray {
  val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
  while(output.size()<=4*1024*1024) {val count=input.read(buffer);if(count<0)break;output.write(buffer,0,count)}
  return output.toByteArray()
 }
 private fun artworkUri(uri:String):ByteArray? {
  val parsed=android.net.Uri.parse(uri)
  if(parsed.scheme in listOf("content","android.resource","file")) return service.contentResolver.openInputStream(parsed)?.use {artworkBytes(it)}
  if(parsed.scheme!="https") return null
  var url=java.net.URL(uri)
  repeat(4) {
   if(url.protocol!="https") return null
   val connection=url.openConnection() as java.net.HttpURLConnection
   connection.connectTimeout=2000;connection.readTimeout=2000;connection.instanceFollowRedirects=false
   val cancel=Runnable {connection.disconnect()};handler.postDelayed(cancel,2500)
   try {
    val code=connection.responseCode
    if(code in 200..299) return connection.inputStream.use {artworkBytes(it)}
    if(code !in listOf(301,302,303,307,308)) return null
    val next=connection.getHeaderField("Location")?.takeIf {it.length<=8192} ?: return null
    url=java.net.URL(url,next)
   } finally {handler.removeCallbacks(cancel);connection.disconnect()}
  }
  return null
 }
 private fun decodeArtwork(bytes:ByteArray):android.graphics.Bitmap? {
  if(bytes.size>4*1024*1024) return null
  val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
  android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
  if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth.toLong()*bounds.outHeight>16_777_216) return null
  val options=android.graphics.BitmapFactory.Options().apply {inSampleSize=1}
  while(bounds.outWidth/options.inSampleSize>2048 || bounds.outHeight/options.inSampleSize>2048) options.inSampleSize*=2
  return android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
 }
 private fun loadArtwork(controller: MediaController, bitmap: android.graphics.Bitmap?, uris: List<String>): JSONObject? {
  var best=bitmap;var owned=false
  fun consider(image:android.graphics.Bitmap) {
   if(best==null || image.width.toLong()*image.height>best!!.width.toLong()*best!!.height) {
    if(owned) best?.recycle()
    best=image;owned=true
   } else if(image!==best) image.recycle()
  }
  try {
   val deadline=SystemClock.elapsedRealtime()+7000
   for(uri in uris) {
    if(SystemClock.elapsedRealtime()>=deadline) break
    runCatching {artworkUri(uri)?.let(::decodeArtwork)?.let(::consider)}
    if(android.net.Uri.parse(uri).scheme=="content") runCatching {
     val cancellation=android.os.CancellationSignal();val cancel=Runnable {cancellation.cancel()};handler.postDelayed(cancel,2500)
     try {consider(service.contentResolver.loadThumbnail(android.net.Uri.parse(uri),android.util.Size(MediaArtwork.MAX_EDGE,MediaArtwork.MAX_EDGE),cancellation))}
     finally {handler.removeCallbacks(cancel)}
    }
   }
   // Compare notification artwork too; a tiny metadata bitmap must not hide a better cover.
   runCatching {
    val candidates=PhoneNotifications.instance?.activeNotifications?.filter { sbn ->
     sbn.packageName==controller.packageName && (sbn.notification.category==android.app.Notification.CATEGORY_TRANSPORT ||
      sbn.notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) ||
      sbn.notification.extras.getString("android.template").orEmpty().contains("MediaStyle"))
    }.orEmpty()
    val notification=(candidates.firstOrNull { sbn ->
     val token=if(android.os.Build.VERSION.SDK_INT>=33) sbn.notification.extras.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION,MediaSession.Token::class.java)
      else sbn.notification.extras.getParcelable<MediaSession.Token>(android.app.Notification.EXTRA_MEDIA_SESSION)
     token==controller.sessionToken
    } ?: candidates.singleOrNull())?.notification
    val drawable=notification?.getLargeIcon()?.loadDrawable(service)
    if(drawable!=null) {
     val width=drawable.intrinsicWidth.coerceAtLeast(1);val height=drawable.intrinsicHeight.coerceAtLeast(1)
     val scale=minOf(1.0,MediaArtwork.MAX_EDGE.toDouble()/maxOf(width,height))
     val image=android.graphics.Bitmap.createBitmap((width*scale).toInt().coerceAtLeast(1),(height*scale).toInt().coerceAtLeast(1),android.graphics.Bitmap.Config.ARGB_8888)
     drawable.setBounds(0,0,image.width,image.height);drawable.draw(android.graphics.Canvas(image));consider(image)
    }
   }
   return best?.let {MediaArtwork.encode(it)}
  } finally {if(owned)best?.recycle()}
 }
 override fun command(action: String,data: JSONObject): JSONObject = onMain {
  check(capability().getString("status")=="available") { "Enable media and notification access" }
  if (action=="refresh") { watch(); return@onMain JSONObject().put("state","refreshed") }
  val available=sessions(); val requested=data.optString("session")
  val controller=if (requested.isNotEmpty()) available.firstOrNull { ids[it.sessionToken]==requested }
   else available.firstOrNull { it.playbackState?.state==PlaybackState.STATE_PLAYING } ?: available.firstOrNull()
  check(controller!=null) { "The selected phone player ended; choose a current player" }
  val required=when (action) { "play"->PlaybackState.ACTION_PLAY;"pause"->PlaybackState.ACTION_PAUSE;"next"->PlaybackState.ACTION_SKIP_TO_NEXT;"previous"->PlaybackState.ACTION_SKIP_TO_PREVIOUS;"seek"->PlaybackState.ACTION_SEEK_TO;else->error("Unsupported media control") }
  check(((controller.playbackState?.actions ?: 0L) and required)!=0L) { "Media app does not support this control" }
  val controls=controller.transportControls
  when (action) {
   "play"->controls.play();"pause"->controls.pause();"next"->controls.skipToNext();"previous"->controls.skipToPrevious()
   "seek"->{
    data.optString("track_id").takeIf {it.isNotEmpty()}?.let {expected ->
     val m=controller.metadata
     val title=Protocol.text((m?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf {it.isNotBlank()} ?: m?.description?.title?.toString()).orEmpty().replace("\u0000",""),512)
     val artist=Protocol.text((m?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf {it.isNotBlank()} ?: m?.description?.subtitle?.toString()).orEmpty().replace("\u0000",""),512)
     val album=Protocol.text(m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),512)
     val track=Protocol.text(m?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: m?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI) ?: "$title:$artist:$album",1024)
     check(track==expected) {"The phone track changed before seeking"}
    }
    val p=data.getLong("position"); val duration=controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L; require(p in 0..86400000 && (duration<=0 || p<=duration)); controls.seekTo(p) }
  }
  JSONObject().put("state","submitted_to_phone_player")
 }
}

/** Laptop-origin metadata may drive remote Android controls but is never published as phone media. */
class DesktopMediaFeature(service: CompanionService) : Module(service,"desktop_media") {
 @Volatile private var snapshot="[]"
 internal val systemControls= LaptopMediaSession(service)
 private val artwork=linkedMapOf<String,JSONObject>()
 override fun capability()=if (service.getSharedPreferences("settings",0).getBoolean("enable.media",false)) cap(id) else cap(id,"disabled","Enable media on your phone")
 @Synchronized fun players(): JSONArray=if (capability().getString("status")=="available" && service.connection.ready && service.connection.desktopAvailable(id)) JSONArray(snapshot).apply {
  for (i in 0 until length()) { val p=getJSONObject(i);artwork[p.optString("artwork_id")]?.let {p.put("artwork",it)} }
 } else JSONArray()
 @Synchronized fun clear() { snapshot="[]";artwork.clear();systemControls.clear();service.uiChanged() }
 fun close() { clear();systemControls.close() }
 @Synchronized fun receive(data: JSONObject) {
  if (data.optString("origin")!="desktop" || capability().getString("status")!="available") return
  if (data.optString("kind")=="artwork") {
   require(data.toString().toByteArray().size < Protocol.MAX_FRAME-1024)
   val session=data.getString("session");val track=data.getString("track_id");val artId=data.getString("artwork_id")
   require(Protocol.validId(session) && track.toByteArray().size<=1024 && MediaArtwork.validId(artId))
   val players=JSONArray(snapshot)
   val player=(0 until players.length()).map { players.getJSONObject(it) }.firstOrNull { it.optString("session")==session && it.optString("track_id")==track && it.optString("artwork_id")==artId }
   val image=data.getJSONObject("artwork");MediaArtwork.bytes(image,artId)
   artwork[artId]=image;while(artwork.size>32)artwork.remove(artwork.keys.first())
   if (player!=null) { systemControls.update();service.uiChanged() };return
  }
  require(data.toString().toByteArray().size<=64000)
  val players=data.getJSONArray("players"); require(players.length()<=16)
  val ids=mutableSetOf<String>()
  for (i in 0 until players.length()) {
   val p=players.getJSONObject(i)
   require(p.getString("origin")=="desktop" && Protocol.validId(p.getString("session")) && ids.add(p.getString("session")))
   for (key in listOf("title","artist","album","app_name","package")) require(p.optString(key).toByteArray().size<=512)
   for (key in listOf("position","duration")) require(p.getLong(key) in 0..86400000)
   val actions=p.getJSONArray("actions"); require(actions.length()<=5)
   for (j in 0 until actions.length()) require(actions.getString(j) in listOf("play","pause","next","previous","seek"))
   require(p.optString("track_id").toByteArray().size<=1024)
   if (p.has("artwork_id")) require(MediaArtwork.validId(p.getString("artwork_id")))
   p.optJSONObject("artwork")?.let { MediaArtwork.bytes(it) }
  }
  if (players.length()==0) artwork.clear()
  val next=players.toString(); if (snapshot!=next) { snapshot=next; systemControls.update();service.uiChanged() }
 }
 // Laptop commands belong only in Connection.request; they cannot be dispatched
 // back to the phone through its feature command queue.
 override fun command(action: String,data: JSONObject): JSONObject = error("Laptop media is display-only on this command route")
}
