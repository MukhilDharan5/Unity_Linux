package org.unityconnect.companion

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.media.VolumeProvider
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaptopMediaSessionTest {
 private val id="ef".repeat(16)
 private fun idle()=shadowOf(Looper.getMainLooper()).idle()
 private fun await(test:()->Boolean) {
  val end=System.nanoTime()+5_000_000_000
  while(!test()) { check(System.nanoTime()<end) { "Timed out" };idle();Thread.sleep(10) }
 }
 private fun player(session:String=id,playing:Boolean=true)=JSONObject().put("origin","desktop").put("session",session).put("track_id","/track/1")
  .put("app_name","Laptop player").put("package","org.mpris.MediaPlayer2.test").put("title","Laptop track").put("artist","Laptop artist").put("album","Album")
  .put("duration",180000).put("position",12000).put("speed",1.0).put("playing",playing).put("active",true).put("actions",JSONArray(listOf("play","pause","previous","next","seek")))
 private fun metadata(vararg players:JSONObject)=JSONObject().put("origin","desktop").put("players",JSONArray(players.toList()))
 private fun caps(peer:TestConnection.Peer)=peer.send(JSONObject().put("type","capabilities").put("capabilities",JSONArray(listOf(cap("lock"),cap("desktop_media"),cap("sound"),cap("media_artwork")))))
 private fun callback(session:MediaSession):MediaSession.Callback {
  val handler=ReflectionHelpers.getField<Any>(session,"mCallback")
  return ReflectionHelpers.getField(handler,"mCallback")
 }
 private fun respond(peer:TestConnection.Peer,request:JSONObject) { peer.send(JSONObject().put("type","result").put("id",request.getString("id")).put("ok",true).put("data",JSONObject()).put("error",""));Thread.sleep(20);idle() }
 private fun request(peer:TestConnection.Peer)=peer.next { it.optString("type")=="request" }
 @Test fun nativeMediaNotificationAndSessionRouteEveryControlToTheExactLaptopPlayer() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  service.getSharedPreferences("settings",0).edit().putBoolean("enable.media",true).commit()
  try { TestConnection(service,true).use { link ->
   caps(link.lan);caps(link.bluetooth);await { service.connection.desktopAvailable("desktop_media") }
   val feature=service.features.desktopMedia
   feature.receive(metadata(player(),player("ab".repeat(16))))
   await { feature.systemControls.sessions().size==2 }
   val session=feature.systemControls.sessions().getValue(id)
   assertTrue(session.isActive)
   val state=ReflectionHelpers.getField<PlaybackState>(session,"mPlaybackState")
   assertEquals(PlaybackState.STATE_PLAYING,state.state);assertEquals(12000L,state.position)
   assertTrue(state.actions and PlaybackState.ACTION_SEEK_TO !=0L)
   val notification=service.getSystemService(NotificationManager::class.java).activeNotifications.first { it.tag==id }.notification
   assertEquals("Laptop track",notification.extras.getString(Notification.EXTRA_TITLE))
   assertEquals("Laptop artist",notification.extras.getString(Notification.EXTRA_TEXT))
   assertNotNull(notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION))
   assertEquals(3,notification.actions.size)
   assertTrue(link.bluetooth.messages.none { it.optString("type")=="request" })
   val cb=callback(session)
   for ((action,gesture) in listOf<Pair<String,()->Unit>>("pause" to {cb.onPause()},"play" to {cb.onPlay()},"next" to {cb.onSkipToNext()},"previous" to {cb.onSkipToPrevious()},"seek" to {cb.onSeekTo(45000)})) {
    gesture();val req=request(link.bluetooth)
    assertEquals("desktop_media",req.getString("feature"));assertEquals(action,req.getString("action"));assertEquals(id,req.getJSONObject("data").getString("session"))
    if(action=="seek") {assertEquals(45000,req.getJSONObject("data").getInt("position"));assertEquals("/track/1",req.getJSONObject("data").getString("track_id"))}
    respond(link.bluetooth,req)
   }
   // The notification action follows the same route as the system-session callback.
   notification.actions.last().actionIntent.send();idle()
   val next=request(link.bluetooth);assertEquals("next",next.getString("action"));assertEquals(id,next.getJSONObject("data").getString("session"));respond(link.bluetooth,next)
   feature.receive(metadata(player(playing=false)));idle()
   assertEquals(1,feature.systemControls.sessions().size)
   assertEquals(PlaybackState.STATE_PAUSED,ReflectionHelpers.getField<PlaybackState>(session,"mPlaybackState").state)
   assertTrue(session.isActive)
   feature.receive(metadata(player().put("actions",JSONArray(listOf("play","pause")))));idle()
   cb.onSeekTo(1000);cb.onSkipToNext();idle();assertTrue(link.bluetooth.messages.none {it.optString("type")=="request"})
   feature.clear();idle();assertTrue(feature.systemControls.sessions().isEmpty());assertFalse(session.isActive)
   cb.onPlay();idle();assertTrue(link.bluetooth.messages.none {it.optString("type")=="request"})
   assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.none {it.notification.channelId==LaptopMediaSession.CHANNEL})
  } } finally { controller.destroy();idle() }
 }
 @Test fun systemVolumeControlsLaptopOutputAndExternalUpdatesStayQuiet() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  service.getSharedPreferences("settings",0).edit().putBoolean("enable.media",true).commit()
  try { TestConnection(service).use { link ->
   caps(link.lan);await { service.connection.desktopAvailable("sound") }
   val sound=JSONObject().put("origin","desktop").put("available",true).put("volume",50).put("muted",false).put("output","cd".repeat(16)).put("name","Speakers").put("error","")
   service.features.sound.receive(sound);service.features.desktopMedia.receive(metadata(player()));idle()
   val session=service.features.desktopMedia.systemControls.sessions().getValue(id)
   val volume=ReflectionHelpers.getField<VolumeProvider>(session,"mVolumeProvider")
   assertEquals(VolumeProvider.VOLUME_CONTROL_ABSOLUTE,volume.volumeControl)
   volume.onSetVolumeTo(88);idle();val req=request(link.lan)
   assertEquals("sound",req.getString("feature"));assertEquals("desktop",req.getJSONObject("data").getString("source"));assertEquals(88,req.getJSONObject("data").getInt("volume"));respond(link.lan,req)
   service.features.sound.receive(sound.put("volume",65));idle();assertEquals(65,volume.currentVolume)
   assertTrue(link.lan.messages.none {it.optString("type")=="request"})
   service.getSharedPreferences("settings",0).edit().putBoolean("enable.media",false).commit();service.features.publish();idle()
   assertTrue(service.features.desktopMedia.systemControls.sessions().isEmpty())
  } } finally {controller.destroy();idle()}
 }
 private fun cover():JSONObject {
  val bitmap=Bitmap.createBitmap(1536,1024,Bitmap.Config.ARGB_8888)
  bitmap.eraseColor(android.graphics.Color.rgb(40,120,200))
  return try { MediaArtwork.encode(bitmap) } finally {bitmap.recycle()}
 }
 @Test fun phoneArtworkPrefersTheOriginalUriAndSendsFullCoversOnceOnTheBulkLink() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  val listener=Robolectric.buildService(PhoneNotifications::class.java).create();PhoneNotifications.instance=listener.get()
  service.getSharedPreferences("settings",0).edit().putBoolean("enable.media",true).commit()
  val original=Bitmap.createBitmap(1600,1200,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.BLUE)}
  val tiny=Bitmap.createScaledBitmap(original,64,48,true)
  val file=java.io.File(service.cacheDir,"original-cover.png");file.outputStream().use {original.compress(Bitmap.CompressFormat.PNG,100,it)}
  val native=MediaSession(service,"Native fixture")
  val media=MediaController(service,native.sessionToken)
  shadowOf(media).setPackageName("player.native")
  shadowOf(media).setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"Native phone track")
   .putString(MediaMetadata.METADATA_KEY_ARTIST,"Artist").putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,tiny)
   .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI,android.net.Uri.fromFile(file).toString()).putLong(MediaMetadata.METADATA_KEY_DURATION,180000).build())
  shadowOf(media).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED,12000,0f).setActions(PlaybackState.ACTION_PLAY).build())
  shadowOf(service.getSystemService(MediaSessionManager::class.java)).addController(media)
  try { TestConnection(service,true).use {link ->
   caps(link.lan);caps(link.bluetooth);await {service.connection.desktopAvailable("media_artwork")}
   service.features.media.watch()
   await {link.lan.messages.any {it.optString("feature")=="media" && it.optJSONObject("data")?.optString("kind")=="artwork"}}
   val full=link.lan.next {it.optJSONObject("data")?.optString("kind")=="artwork"}.getJSONObject("data")
   assertEquals("phone",full.getString("origin"));assertEquals(1024,full.getJSONObject("artwork").getInt("width"))
   assertEquals(1024,service.features.media.players().getJSONObject(0).getJSONObject("artwork").getInt("width"))
   val small=link.bluetooth.next {it.optString("feature")=="media" && it.optJSONObject("data")?.optString("artwork_id")==full.getString("artwork_id")}.getJSONObject("data")
   assertTrue(small.getJSONObject("artwork").getInt("width")<=128);assertTrue(small.toString().toByteArray().size<=60000)
   shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));Thread.sleep(50);idle()
   assertTrue(link.lan.messages.none {it.optJSONObject("data")?.optString("kind")=="artwork"})
   assertTrue(link.bluetooth.messages.none {it.optJSONObject("data")?.optString("kind")=="artwork"})
  } } finally { PhoneNotifications.instance=null;listener.destroy();controller.destroy();native.release();original.recycle();tiny.recycle();idle() }
 }
 @Test fun fullResolutionArtworkSurvivesMetadataRefreshAndOutOfOrderLinksButNeverLeaksToANewTrack() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  service.getSharedPreferences("settings",0).edit().putBoolean("enable.media",true).commit()
  try { TestConnection(service).use {link ->
   caps(link.lan);await {service.connection.desktopAvailable("desktop_media")}
   val image=cover();assertEquals(1024,image.getInt("width"));assertTrue(image.getJSONObject("preview").getInt("width")<=128)
   val art=JSONObject().put("kind","artwork").put("origin","desktop").put("session",id).put("track_id","/track/1").put("artwork_id",image.getString("id")).put("artwork",image)
   val p=player().put("artwork_id",image.getString("id")).put("artwork",image.getJSONObject("preview"))
   val feature=service.features.desktopMedia
   feature.receive(art);feature.receive(metadata(p));idle()
   assertEquals(1024,feature.players().getJSONObject(0).getJSONObject("artwork").getInt("width"))
   feature.receive(metadata(p.put("position",24000)));idle()
   assertEquals(1024,feature.players().getJSONObject(0).getJSONObject("artwork").getInt("width"))
   feature.receive(metadata(player().put("track_id","/track/new")));feature.receive(art);idle()
   assertNull(feature.players().getJSONObject(0).optJSONObject("artwork"))
   assertThrows(IllegalArgumentException::class.java) {feature.receive(JSONObject(art.toString()).put("artwork_id","00".repeat(32)))}
   assertThrows(IllegalArgumentException::class.java) {MediaArtwork.bytes(JSONObject(image.toString()).put("width",8192))}
   assertTrue(art.toString().toByteArray().size<Protocol.MAX_FRAME-1024)
   link.close();idle();assertTrue(feature.systemControls.sessions().isEmpty())
  } } finally {controller.destroy();idle()}
 }
}
