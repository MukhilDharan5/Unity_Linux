package org.unityconnect.companion
import android.media.AudioManager
import android.os.Looper
import android.widget.Button
import android.widget.SeekBar
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

@Implements(AudioManager::class)
class FixedSoundManager: org.robolectric.shadows.ShadowAudioManager() { @Implementation fun isVolumeFixed()=true }
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SoundTest {
 private fun await(test: () -> Boolean) {
  val end=System.nanoTime()+5_000_000_000
  while (!test()) { check(System.nanoTime()<end) { "Timed out" };shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10) }
 }
 private fun enable(service: CompanionService) { service.getSharedPreferences("settings",0).edit().putBoolean("enable.media",true).commit() }
 private fun laptop(volume: Int=70,muted: Boolean=false,output: String="ef".repeat(16))=JSONObject().put("origin","desktop").put("available",true).put("volume",volume).put("muted",muted).put("name","Laptop speakers").put("error","").put("output",output)
 private fun caps(peer: TestConnection.Peer)=peer.send(JSONObject().put("type","capabilities").put("capabilities",JSONArray().put(cap("lock")).put(cap("sound"))))
 @Test fun phoneUsesOnlyMusicVolumeAndAbsoluteMuteWithoutAnActivePlayer() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  try {
   enable(service);val audio=service.getSystemService(AudioManager::class.java)
   assertEquals("available",service.features.sound.capability().getString("status"))
   val others=listOf(AudioManager.STREAM_RING,AudioManager.STREAM_NOTIFICATION,AudioManager.STREAM_VOICE_CALL).associateWith { audio.getStreamVolume(it) }
   val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
   service.features.sound.command("volume",JSONObject().put("source","phone").put("volume",100));assertEquals(max,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
   service.features.sound.command("volume",JSONObject().put("source","phone").put("volume",40));assertEquals((max*.4).toInt(),audio.getStreamVolume(AudioManager.STREAM_MUSIC))
   service.features.sound.command("mute",JSONObject().put("source","phone").put("muted",true));assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
   service.features.sound.command("mute",JSONObject().put("source","phone").put("muted",true));assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
   service.features.sound.command("mute",JSONObject().put("source","phone").put("muted",false));assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC))
   others.forEach { (stream,volume)->assertEquals(volume,audio.getStreamVolume(stream)) }
   for (value in listOf<Any>(-1,101,.5,4294967300L,"20")) assertThrows(IllegalArgumentException::class.java) { service.features.sound.command("volume",JSONObject().put("source","phone").put("volume",value)) }
   assertThrows(IllegalArgumentException::class.java) { service.features.sound.command("volume",JSONObject().put("source","desktop").put("volume",20)) }
   service.getSharedPreferences("settings",0).edit().putBoolean("enable.media",false).commit()
   assertThrows(IllegalStateException::class.java) { service.features.sound.command("volume",JSONObject().put("source","phone").put("volume",20)) }
  } finally { controller.destroy();shadowOf(Looper.getMainLooper()).idle() }
 }
 @Test @Config(shadows=[FixedSoundManager::class]) fun fixedVolumePolicyDisablesSetters() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  try {
   enable(service);service.features.sound.watch();shadowOf(Looper.getMainLooper()).idle()
   assertFalse(service.features.sound.phoneState().getBoolean("available"))
   assertThrows(IllegalStateException::class.java) { service.features.sound.command("volume",JSONObject().put("source","phone").put("volume",100)) }
   assertThrows(IllegalStateException::class.java) { service.features.sound.command("mute",JSONObject().put("source","phone").put("muted",true)) }
  } finally { controller.destroy();shadowOf(Looper.getMainLooper()).idle() }
 }
 @Test fun snapshotsAreDisplayOnlyAndReplaysCannotUndoVolumeKeys() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get();enable(service)
  try { TestConnection(service,true).use { link ->
   caps(link.lan);caps(link.bluetooth);await { service.connection.desktopAvailable("sound") && service.features.sound.phoneState().has("volume") }
   val audio=service.getSystemService(AudioManager::class.java);val original=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
   link.lan.send(JSONObject().put("type","event").put("feature","sound").put("data",laptop(90)));await { service.features.sound.desktopState().optInt("volume")==90 }
   assertEquals(original,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
   service.features.sound.receive(JSONObject().put("origin","phone"));assertEquals(90,service.features.sound.desktopState().getInt("volume"))
   val command=JSONObject().put("type","command").put("id","ab".repeat(16)).put("feature","sound").put("action","volume").put("data",JSONObject().put("source","phone").put("volume",100))
   link.lan.send(command);await { audio.getStreamVolume(AudioManager.STREAM_MUSIC)==audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
   assertTrue(link.lan.next { it.optString("type")=="result" && it.optString("id")==command.getString("id") }.getBoolean("ok"))
   audio.setStreamVolume(AudioManager.STREAM_MUSIC,3,0);link.bluetooth.send(command)
   assertTrue(link.bluetooth.next { it.optString("type")=="result" && it.optString("id")==command.getString("id") }.getBoolean("ok"));assertEquals(3,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
   shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));Thread.sleep(100)
   val messages=mutableListOf<JSONObject>();link.lan.messages.drainTo(messages);link.bluetooth.messages.drainTo(messages)
   assertTrue(messages.none { it.optString("type")=="request" || it.optString("type")=="command" })
   assertTrue(messages.filter { it.optString("type")=="event" && it.optString("feature")=="sound" }.all { it.getJSONObject("data").optString("origin")=="phone" })
   link.bluetooth.send(JSONObject(command.toString()).put("data",JSONObject().put("source","phone").put("volume",50)));await { link.bluetooth.closed };assertEquals(3,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
  } } finally { controller.destroy();shadowOf(Looper.getMainLooper()).idle() }
 }
 @Test fun actualSlidersMuteButtonsAndOutputSwitchesTargetTheCorrectDevice() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get();enable(service)
  try { TestConnection(service).use { link ->
   caps(link.lan);await { service.connection.desktopAvailable("sound") };link.lan.send(JSONObject().put("type","event").put("feature","sound").put("data",laptop()));await { service.features.sound.desktopState().has("volume") }
   org.robolectric.RuntimeEnvironment.setQualifiers("w393dp-h852dp-notnight-xhdpi")
   val activityController=Robolectric.buildActivity(MediaActivity::class.java).setup();val activity=activityController.get()
   try {
    shadowOf(Looper.getMainLooper()).idle();assertEquals(AudioManager.STREAM_MUSIC,activity.volumeControlStream);val slider=activity.findViewById<SeekBar>(R.id.laptop_volume)
    assertTrue(slider.isEnabled);assertEquals(70,slider.progress);slider.progress=20;shadowOf(Looper.getMainLooper()).idle()
    var messages=mutableListOf<JSONObject>();link.lan.messages.drainTo(messages);assertTrue(messages.none { it.optString("type")=="request" })
    val listener=shadowOf(slider).onSeekBarChangeListener
    listener.onStartTrackingTouch(slider);slider.progress=40;listener.onProgressChanged(slider,40,true);listener.onStopTrackingTouch(slider)
    val request=link.lan.next { it.optString("type")=="request" };assertEquals("sound",request.getString("feature"));assertEquals("desktop",request.getJSONObject("data").getString("source"));assertEquals(40,request.getJSONObject("data").getInt("volume"))
    link.lan.send(JSONObject().put("type","result").put("id",request.getString("id")).put("ok",true).put("data",JSONObject().put("sound",laptop(40))).put("error",""));await { slider.isEnabled && slider.progress==40 }
    activity.findViewById<Button>(R.id.laptop_mute).performClick();val mute=link.lan.next { it.optString("type")=="request" };assertEquals("mute",mute.getString("action"));assertTrue(mute.getJSONObject("data").getBoolean("muted"))
    link.lan.send(JSONObject().put("type","result").put("id",mute.getString("id")).put("ok",true).put("data",JSONObject().put("sound",laptop(40,true))).put("error",""));await { activity.findViewById<Button>(R.id.laptop_mute).text=="Unmute" }
    listener.onStartTrackingTouch(slider);slider.progress=10;link.lan.send(JSONObject().put("type","event").put("feature","sound").put("data",laptop(80,false,"cd".repeat(16))))
    await { service.features.sound.desktopState().optString("output")=="cd".repeat(16) };listener.onStopTrackingTouch(slider);shadowOf(Looper.getMainLooper()).idle()
    messages=mutableListOf();link.lan.messages.drainTo(messages);assertTrue(messages.none { it.optString("type")=="request" })
    val phone=activity.findViewById<SeekBar>(R.id.phone_volume);val phoneListener=shadowOf(phone).onSeekBarChangeListener
    phoneListener.onStartTrackingTouch(phone);phone.progress=100;phoneListener.onStopTrackingTouch(phone)
    val audio=service.getSystemService(AudioManager::class.java);assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    activity.findViewById<Button>(R.id.phone_mute).performClick();assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC));await { activity.findViewById<Button>(R.id.phone_mute).text=="Unmute" }
    assertEquals("No active players",activity.findViewById<android.widget.Spinner>(R.id.media_player_picker).adapter.getItem(0))
    val root=activity.findViewById<android.view.View>(android.R.id.content);val width=786;val height=1704
    root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY));root.layout(0,0,width,height)
    val bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);root.draw(android.graphics.Canvas(bitmap))
    val out=java.io.File("build/reports/unity-preview/sound-both-light.png");out.parentFile!!.mkdirs();out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
   } finally { activityController.pause().stop().destroy() }
  } } finally { controller.destroy();shadowOf(Looper.getMainLooper()).idle() }
 }
}
