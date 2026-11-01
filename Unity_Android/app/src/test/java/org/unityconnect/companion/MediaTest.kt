package org.unityconnect.companion

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Looper
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class MediaTest {
 private fun await(test: () -> Boolean) {
  val end=System.nanoTime()+5_000_000_000
  while (!test()) { check(System.nanoTime()<end) { "Timed out" }; shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
 }
 private fun player(service: CompanionService,name: String,title: String): MediaController {
  val session=MediaSession(service,name)
  val controller=MediaController(service,session.sessionToken)
  shadowOf(controller).setPackageName(name)
  shadowOf(controller).setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title).putString(MediaMetadata.METADATA_KEY_ARTIST,"Artist").putLong(MediaMetadata.METADATA_KEY_DURATION,180000).build())
  shadowOf(controller).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED,12000,1f,0).setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO).build())
  shadowOf(service.getSystemService(MediaSessionManager::class.java)).addController(controller)
  return controller
 }
 private fun laptop()=JSONObject().put("origin","desktop").put("players",JSONArray().put(JSONObject()
  .put("origin","desktop").put("session","ef".repeat(16)).put("track_id","/track/1").put("app_name","Laptop player").put("package","org.mpris.MediaPlayer2.test")
  .put("title","Laptop track").put("artist","Laptop artist").put("album","").put("playing",false).put("active",true).put("position",24000).put("duration",180000)
  .put("actions",JSONArray(listOf("play","pause","next","previous","seek")))))
 private fun desktopCaps(peer: TestConnection.Peer) = peer.send(JSONObject().put("type","capabilities").put("capabilities",JSONArray().put(cap("lock")).put(cap("desktop_media"))))
 private fun coverFile(service:CompanionService,name:String,width:Int,height:Int):String {
  val image=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888)
  val file=java.io.File(service.cacheDir,name)
  try {file.outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it)}} finally {image.recycle()}
  return android.net.Uri.fromFile(file).toString()
 }
 @Test fun phoneArtworkComparesAllImageUrisInsteadOfKeepingTheFirstSmallThumbnail() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  val listener=Robolectric.buildService(PhoneNotifications::class.java).create();PhoneNotifications.instance=listener.get()
  service.getSharedPreferences("settings",0).edit().putBoolean("enable.media",true).commit()
  val media=player(service,"native.artwork","Original cover")
  shadowOf(media).setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"Original cover")
   .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI,coverFile(service,"small.jpg",64,64))
   .putString(MediaMetadata.METADATA_KEY_ART_URI,coverFile(service,"original.jpg",1600,1200)).build())
  try {TestConnection(service).use {
   await {service.features.media.players().optJSONObject(0)?.optJSONObject("artwork")?.optInt("width")==1024}
   val image=service.features.media.players().getJSONObject(0).getJSONObject("artwork")
   assertEquals(768,image.getInt("height"));assertTrue(MediaArtwork.bytes(image,image.getString("id")).size<=MediaArtwork.MAX_BYTES)
   assertEquals(0L,shadowOf(media.transportControls).lastPerformedAction)
  }}finally {PhoneNotifications.instance=null;listener.destroy();controller.destroy();shadowOf(Looper.getMainLooper()).idle()}
 }
 @Test fun aSmallUriCannotReplaceABetterEmbeddedPhoneCover() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  val listener=Robolectric.buildService(PhoneNotifications::class.java).create();PhoneNotifications.instance=listener.get()
  service.getSharedPreferences("settings",0).edit().putBoolean("enable.media",true).commit()
  val media=player(service,"native.embedded.artwork","Embedded original")
  val bitmap=android.graphics.Bitmap.createBitmap(1200,1200,android.graphics.Bitmap.Config.ARGB_8888)
  shadowOf(media).setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"Embedded original")
   .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,bitmap).putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI,coverFile(service,"low-res.jpg",128,128)).build())
  try {TestConnection(service).use {
   await {service.features.media.players().optJSONObject(0)?.optJSONObject("artwork")?.optInt("width")==1024}
   assertFalse(bitmap.isRecycled)
  }}finally {PhoneNotifications.instance=null;listener.destroy();controller.destroy();shadowOf(Looper.getMainLooper()).idle();bitmap.recycle()}
 }
 @Test fun phoneSeekRejectsAnOldTrackBeforeDispatchingToTheMediaApp() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  val listener=Robolectric.buildService(PhoneNotifications::class.java).create();PhoneNotifications.instance=listener.get()
  service.getSharedPreferences("settings",0).edit().putBoolean("enable.media",true).commit()
  val media=player(service,"native.seek.guard","Current song")
  try {
   assertThrows(IllegalStateException::class.java) {service.features.media.command("seek",JSONObject().put("track_id","Old song:Artist:").put("position",12000))}
   assertEquals(0L,shadowOf(media.transportControls).lastPerformedAction)
   service.features.media.command("seek",JSONObject().put("track_id","Current song:Artist:").put("position",12000))
   assertEquals(PlaybackState.ACTION_SEEK_TO,shadowOf(media.transportControls).lastPerformedAction)
  }finally {PhoneNotifications.instance=null;listener.destroy();controller.destroy();shadowOf(Looper.getMainLooper()).idle()}
 }
 @Test fun nativeSessionsHaveStableIdsAndRemoteSnapshotsNeverBecomePhoneSessions() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  val listener=Robolectric.buildService(PhoneNotifications::class.java).create();PhoneNotifications.instance=listener.get()
  service.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putBoolean("enable.media",true).commit()
  val first=player(service,"player.first","Phone first");val second=player(service,"player.second","Phone second")
  player(service,service.packageName,"Unity remote playback")
  try { TestConnection(service).use { link ->
   desktopCaps(link.lan);await { service.connection.desktopAvailable("desktop_media") && service.features.media.players().length()==2 }
   val initial=service.features.media.players();val ids=(0 until initial.length()).associate { initial.getJSONObject(it).getString("package") to initial.getJSONObject(it).getString("session") }
   assertEquals(2,ids.size);assertNotEquals(ids["player.first"],ids["player.second"])
   service.features.media.watch();await { service.features.media.players().length()==2 }
   assertEquals(ids["player.second"],service.features.media.players().getJSONObject(1).getString("session"))
   link.lan.send(JSONObject().put("type","event").put("feature","desktop_media").put("data",laptop()))
   await { service.features.desktopMedia.players().length()==1 }
   assertEquals(2,service.features.media.players().length())
   service.features.media.command("next",JSONObject().put("session",ids["player.second"]))
   assertEquals(PlaybackState.ACTION_SKIP_TO_NEXT,shadowOf(second.transportControls).lastPerformedAction)
   assertEquals(0L,shadowOf(first.transportControls).lastPerformedAction)
   assertThrows(IllegalStateException::class.java) { service.features.media.command("next",JSONObject().put("session","ff".repeat(16))) }
   // Rendering/receiving laptop state emits neither requests nor laptop-origin phone events.
   shadowOf(Looper.getMainLooper()).idle();Thread.sleep(100)
   val outgoing=mutableListOf<JSONObject>();link.lan.messages.drainTo(outgoing)
   assertTrue(outgoing.none { it.optString("type")=="request" })
   assertTrue(outgoing.filter { it.optString("type")=="event" && it.optString("feature")=="media" }.all { it.getJSONObject("data").optString("origin")=="phone" && !it.toString().contains("Laptop track") && !it.toString().contains("Unity remote playback") })
   service.features.desktopMedia.receive(laptop().put("origin","phone"));assertEquals(1,service.features.desktopMedia.players().length())
   service.getSharedPreferences("settings",0).edit().putBoolean("enable.media",false).commit();service.features.publish()
   assertEquals(0,service.features.desktopMedia.players().length())
  } } finally { PhoneNotifications.instance=null;listener.destroy();controller.destroy();shadowOf(Looper.getMainLooper()).idle() }
 }
 @Test fun timelineRefreshesWithoutPauseAndKeepsDurationDuringPartialMetadata() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  val listener=Robolectric.buildService(PhoneNotifications::class.java).create();PhoneNotifications.instance=listener.get()
  service.getSharedPreferences("settings",0).edit().putBoolean("enable.media",true).commit()
  val media=player(service,"native.timeline","Timeline track")
  try {TestConnection(service).use {
   await {service.features.media.players().length()==1}
   val artwork=android.graphics.Bitmap.createBitmap(300,300,android.graphics.Bitmap.Config.ARGB_8888)
   shadowOf(media).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING,12000,1f,android.os.SystemClock.elapsedRealtime().coerceAtLeast(1)).setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO).build())
   // The player transiently omits duration while changing its cover, without
   // issuing a playback-state callback. The periodic query must recover it.
   shadowOf(media).setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"Timeline track").putString(MediaMetadata.METADATA_KEY_ARTIST,"Artist").putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,artwork).build())
   shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
   await {service.features.media.players().getJSONObject(0).optJSONObject("artwork")!=null}
   val current=service.features.media.players().getJSONObject(0)
   assertEquals(180000L,current.getLong("duration"));assertTrue(current.getLong("position")>12000)
   assertEquals(0L,shadowOf(media.transportControls).lastPerformedAction)
   shadowOf(media).setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"Different track").build())
   shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
   assertEquals(0L,service.features.media.players().getJSONObject(0).getLong("duration"))
   shadowOf(media).setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE,"Display-only title").putLong(MediaMetadata.METADATA_KEY_DURATION,90000).build())
   shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
   assertEquals("Display-only title",service.features.media.players().getJSONObject(0).getString("title"))
  }}finally {PhoneNotifications.instance=null;listener.destroy();controller.destroy();shadowOf(Looper.getMainLooper()).idle()}
 }
 @Test fun repeatedMediaCommandsAcrossLinksExecuteOnceAndCannotChangeContent() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  val listener=Robolectric.buildService(PhoneNotifications::class.java).create();PhoneNotifications.instance=listener.get()
  service.getSharedPreferences("settings",0).edit().putBoolean("enable.media",true).commit()
  val player=player(service,"native.player","Phone track")
  try { TestConnection(service,true).use { link ->
   await { service.features.media.players().length()==1 }
   val id=service.features.media.players().getJSONObject(0).getString("session")
   val command=JSONObject().put("type","command").put("id","ab".repeat(16)).put("feature","media").put("action","next").put("data",JSONObject().put("session",id))
   link.lan.send(command)
   await { shadowOf(player.transportControls).lastPerformedAction==PlaybackState.ACTION_SKIP_TO_NEXT }
   val first=link.lan.next { it.optString("type")=="result" && it.optString("id")==command.getString("id") };assertTrue(first.getBoolean("ok"))
   player.transportControls.play()
   link.bluetooth.send(command)
   val repeated=link.bluetooth.next { it.optString("type")=="result" && it.optString("id")==command.getString("id") }
   assertEquals(first.toString(),repeated.toString());assertEquals(PlaybackState.ACTION_PLAY,shadowOf(player.transportControls).lastPerformedAction)
   link.bluetooth.send(JSONObject(command.toString()).put("action","pause"))
   await { link.bluetooth.closed };assertEquals(PlaybackState.ACTION_PLAY,shadowOf(player.transportControls).lastPerformedAction)
  } } finally { PhoneNotifications.instance=null;listener.destroy();controller.destroy();shadowOf(Looper.getMainLooper()).idle() }
 }
 @Test fun phoneMediaUiSelectsLaptopAndRequestsOnlyExplicitButtonActions() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  val listener=Robolectric.buildService(PhoneNotifications::class.java).create();PhoneNotifications.instance=listener.get()
  service.getSharedPreferences("settings",0).edit().putBoolean("enable.media",true).commit()
  player(service,"native.player","Phone track")
  try { TestConnection(service).use { link ->
   desktopCaps(link.lan);await { service.connection.desktopAvailable("desktop_media") && service.features.media.players().length()==1 }
   link.lan.send(JSONObject().put("type","event").put("feature","desktop_media").put("data",laptop()));await { service.features.desktopMedia.players().length()==1 }
   org.robolectric.RuntimeEnvironment.setQualifiers("w393dp-h852dp-notnight-xhdpi")
   val activityController=Robolectric.buildActivity(MediaActivity::class.java).setup();val activity=activityController.get()
   try {
    shadowOf(Looper.getMainLooper()).idle()
    val picker=activity.findViewById<Spinner>(R.id.media_player_picker);assertEquals(2,picker.adapter.count)
    picker.setSelection(1);shadowOf(Looper.getMainLooper()).idle()
    assertEquals("Laptop track",activity.findViewById<TextView>(R.id.media_track_title).text.toString())
    val root=activity.findViewById<android.view.View>(android.R.id.content)
    val width=786;val height=1704
    root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY));root.layout(0,0,width,height)
    val bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);root.draw(android.graphics.Canvas(bitmap))
    val out=java.io.File("build/reports/unity-preview/media-both-light.png");out.parentFile!!.mkdirs();out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    val before=mutableListOf<JSONObject>();link.lan.messages.drainTo(before);assertTrue(before.none { it.optString("type")=="request" })
    activity.findViewById<Button>(R.id.media_next).performClick()
    val request=link.lan.next { it.optString("type")=="request" }
    assertEquals("desktop_media",request.getString("feature"));assertEquals("next",request.getString("action"));assertEquals("ef".repeat(16),request.getJSONObject("data").getString("session"))
    link.lan.send(JSONObject().put("type","result").put("id",request.getString("id")).put("ok",true).put("data",JSONObject()).put("error",""))
    await { activity.findViewById<Button>(R.id.media_next).isEnabled }
    // Another metadata snapshot does not trigger the command again.
    link.lan.send(JSONObject().put("type","event").put("feature","desktop_media").put("data",laptop()));Thread.sleep(100);shadowOf(Looper.getMainLooper()).idle()
    val after=mutableListOf<JSONObject>();link.lan.messages.drainTo(after);assertTrue(after.none { it.optString("type")=="request" })
    link.lan.send(JSONObject().put("type","capabilities").put("capabilities",JSONArray().put(cap("lock")).put(cap("desktop_media","disabled"))))
    await { service.features.desktopMedia.players().length()==0 && picker.adapter.count==1 }
   } finally { activityController.pause().stop().destroy() }
  } } finally { PhoneNotifications.instance=null;listener.destroy();controller.destroy();shadowOf(Looper.getMainLooper()).idle() }
 }
 @Test fun invalidAndOversizedRemotePlayerListsAreRejected() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  try {
   service.getSharedPreferences("settings",0).edit().putBoolean("enable.media",true).commit()
   for ((key,value) in listOf("session" to "bad","origin" to "phone","duration" to -1,"title" to "x".repeat(513))) {
    val data=laptop();data.getJSONArray("players").getJSONObject(0).put(key,value)
    assertThrows(IllegalArgumentException::class.java) { service.features.desktopMedia.receive(data) }
   }
   val data=laptop();val list=JSONArray();repeat(17) { list.put(data.getJSONArray("players").getJSONObject(0)) };data.put("players",list)
   assertThrows(IllegalArgumentException::class.java) { service.features.desktopMedia.receive(data) }
   assertThrows(IllegalStateException::class.java) { service.features.desktopMedia.command("play",JSONObject()) }
  } finally { controller.destroy();shadowOf(Looper.getMainLooper()).idle() }
 }
}
