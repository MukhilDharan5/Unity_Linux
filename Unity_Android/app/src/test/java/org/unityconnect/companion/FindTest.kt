package org.unityconnect.companion

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FindTest {
 private class Tone(private val playable: Boolean = true): FindSound {
  var active = false; var closes = 0
  override fun play() { active = playable }
  override fun playing() = active
  override fun close() { active = false; closes++ }
 }
 @Test fun silentPhoneUsesAudibleAlarmAndRestoresItsOriginalVolume() {
  val controller = Robolectric.buildService(CompanionService::class.java).create(); val service = controller.get()
  val audio = service.getSystemService(AudioManager::class.java); val tone = Tone()
  val other = listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING).associateWith { audio.getStreamVolume(it) }
  audio.ringerMode = AudioManager.RINGER_MODE_SILENT; audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)
  val find = FindFeature(service) { attributes ->
   assertEquals(AudioAttributes.USAGE_ALARM, attributes.usage); tone
  }
  try {
   assertEquals("ringing", find.command("ring", JSONObject()).getString("state"))
   assertTrue(tone.active); assertTrue(audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0)
   assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
   other.forEach { (stream, volume) -> assertEquals(volume, audio.getStreamVolume(stream)) }
   find.command("stop", JSONObject()); assertFalse(tone.active)
   assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_ALARM))
  } finally { find.stop(); controller.destroy() }
 }
 @Test fun repeatedRingsResetTimeoutAndKeepUserVolumeChanges() {
  val controller = Robolectric.buildService(CompanionService::class.java).create(); val service = controller.get()
  val audio = service.getSystemService(AudioManager::class.java); val tones = mutableListOf<Tone>()
  val find = FindFeature(service) { Tone().also { tones.add(it) } }
  try {
   find.command("ring", JSONObject()); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
   find.command("ring", JSONObject()); assertFalse(tones[0].active)
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11)); assertTrue(tones[1].active)
   audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20)); assertFalse(tones[1].active)
   assertEquals(1, audio.getStreamVolume(AudioManager.STREAM_ALARM))
  } finally { find.stop(); controller.destroy() }
 }
 @Test fun failedPlaybackAndRevokedPermissionCleanUpRingingAndVolume() {
  val controller = Robolectric.buildService(CompanionService::class.java).create(); val service = controller.get()
  val audio = service.getSystemService(AudioManager::class.java); val initial = audio.getStreamVolume(AudioManager.STREAM_ALARM)
  val broken = Tone(false); val find = FindFeature(service) { broken }
  try {
   assertThrows(IllegalStateException::class.java) { find.command("ring", JSONObject()) }
   assertEquals(1, broken.closes); assertEquals(initial, audio.getStreamVolume(AudioManager.STREAM_ALARM))
   val tone = Tone(); val ringing = FindFeature(service) { tone }; ringing.command("ring", JSONObject())
   service.getSharedPreferences("settings", 0).edit().putBoolean("enable.find", false).commit()
   ringing.settingsChanged(); assertFalse(tone.active)
   assertThrows(IllegalStateException::class.java) { ringing.command("ring", JSONObject()) }
  } finally { find.stop(); controller.destroy() }
 }
}
