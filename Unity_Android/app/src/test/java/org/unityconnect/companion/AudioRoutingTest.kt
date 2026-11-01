package org.unityconnect.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioRoutingTest {
 private fun format() = JSONObject().put("token", "01".repeat(32)).put("request_id", "ab".repeat(16))
  .put("rate", 48000).put("channels", 2).put("frames", 480).put("format", "s16le")
 @Test fun unsupportedFormatsAndUnboundedPacketsCannotBeNegotiated() {
  AudioWire.validate(format())
  for ((key, value) in listOf("rate" to 192000, "channels" to 16, "frames" to 48000, "format" to "mp3", "token" to "bad", "request_id" to "../id")) {
   assertThrows(IllegalArgumentException::class.java) { AudioWire.validate(format().put(key, value)) }
  }
 }
 @Test fun rawPcmSurvivesFragmentedInputWithoutBase64OrJson() {
  val pcm = ByteArray(AudioWire.BYTES) { (it % 256).toByte() }
  val bytes = ByteArrayOutputStream().apply { DataOutputStream(this).apply { writeLong(42); write(pcm) } }.toByteArray()
  val fragmented = object : ByteArrayInputStream(bytes) {
   override fun read(target: ByteArray, offset: Int, length: Int): Int = super.read(target, offset, minOf(length, 7))
  }
  val packet = AudioWire.read(DataInputStream(fragmented), 40)
  assertEquals(42L, packet.sequence); assertArrayEquals(pcm, packet.pcm)
 }
 @Test fun replayNegativeSequenceAndTruncationAreRejected() {
  fun packet(sequence: Long, size: Int = AudioWire.BYTES): DataInputStream {
   val output = ByteArrayOutputStream(); DataOutputStream(output).apply { writeLong(sequence); write(ByteArray(size)) }
   return DataInputStream(ByteArrayInputStream(output.toByteArray()))
  }
  assertThrows(IllegalArgumentException::class.java) { AudioWire.read(packet(2), 2) }
  assertThrows(IllegalArgumentException::class.java) { AudioWire.read(packet(-1), -1) }
  assertThrows(java.io.EOFException::class.java) { AudioWire.read(packet(3, 100), 2) }
 }
 @Test fun playbackStartsBeforeBlockingWritesAndGapsDoNotFlushTheOutput() {
  var running = false; var starts = 0
  val bytes = ByteArrayOutputStream()
  val output = PcmPlayback({ running = true; starts++ }, { fail("Packet gaps cannot pause playback") },
   { fail("Packet gaps cannot discard buffered sound") }, { pcm, offset, length ->
    check(running) { "Blocking write would hang while output is stopped" }
    val count = minOf(length, 128); bytes.write(pcm, offset, count); count
   })
  val pcm = ByteArray(AudioWire.BYTES) { it.toByte() }
  for (sequence in listOf(0L, 1L, 20L, 100L)) {
   assertTrue(output.accept(AudioWire.Packet(sequence, pcm, 0), 20) { true })
  }
  assertEquals(1, starts); assertArrayEquals(pcm + pcm + pcm + pcm, bytes.toByteArray())
 }
 @Test fun temporaryFocusLossDropsPacketsAndResumesWithoutRestartingTheStream() {
  var starts = 0; var pauses = 0; var flushes = 0; var writes = 0
  val output = PcmPlayback({ starts++ }, { pauses++ }, { flushes++ }, { _, _, size -> writes++; size })
  val packet = AudioWire.Packet(1, ByteArray(AudioWire.BYTES), 0)
  assertTrue(output.accept(packet, 20) { true })
  output.suspended = true
  repeat(3) { assertFalse(output.accept(packet, 20) { true }) }
  assertEquals(1, pauses); assertEquals(1, flushes); assertEquals(1, writes)
  output.suspended = false
  assertFalse(output.accept(packet, 150) { true })
  assertTrue(output.accept(packet, 20) { true }); assertEquals(2, starts)
  assertFalse(output.accept(packet, 20) { false }); assertEquals(2, writes)
 }
 @Test fun outputFailuresKeepTheNativeErrorAndCannotSpinOnZeroWrites() {
  for (error in listOf(0, -6)) {
   val output = PcmPlayback({}, {}, {}, { _, _, _ -> error })
   val failure = assertThrows(IllegalStateException::class.java) {
    output.accept(AudioWire.Packet(1, ByteArray(AudioWire.BYTES), 0), 0) { true }
   }
   assertTrue(failure.message!!.contains("($error)"))
  }
 }
 @Test fun bluetoothFormatIsBoundedAndPreservesPacketFraming() {
  val format = AudioWire.validate(format().put("rate", 16000).put("channels", 1).put("frames", 160))
  assertEquals(320, format.bytes)
  val pcm = ByteArray(format.bytes) { it.toByte() }
  val output = ByteArrayOutputStream()
  DataOutputStream(output).apply { writeLong(5); write(pcm) }
  val packet = AudioWire.read(DataInputStream(ByteArrayInputStream(output.toByteArray())), 4, format.bytes)
  assertEquals(5L, packet.sequence); assertArrayEquals(pcm, packet.pcm)
  assertThrows(IllegalArgumentException::class.java) { AudioWire.validate(format().put("source", "microphone")) }
 }
 @Test fun phoneCaptureWaitsForConsentIgnoresStaleStopsAndReportsDenialOnItsOriginalLink() {
  val controller = org.robolectric.Robolectric.buildService(CompanionService::class.java).create()
  val service = controller.get()
  service.getSharedPreferences("settings", 0).edit().putBoolean("enable.audio", true).commit()
  try { TestConnection(service, true).use { fixture ->
   val id = "ab".repeat(16)
   fixture.lan.send(JSONObject().put("type", "command").put("id", id).put("feature", "audio").put("action", "start")
    .put("data", format().put("source", "phone")))
   fixture.await { service.features.audio.pendingId() == id }
   assertFalse(service.features.audio.projectionForeground); assertFalse(service.features.audio.playing)
   service.features.audio.command("stop", JSONObject().put("request_id", "cd".repeat(16)))
   assertEquals(id, service.features.audio.pendingId())
   service.features.audio.consent(id, android.app.Activity.RESULT_CANCELED, null)
   val error = fixture.lan.next { it.optString("type") == "result" && it.optString("id") == id }
   assertFalse(error.getBoolean("ok")); assertTrue(error.getString("error").contains("denied"))
   assertNull(service.features.audio.pendingId()); assertFalse(service.features.audio.capturing())
   assertFalse(service.features.audio.projectionForeground); assertTrue(service.connection.ready)
   assertFalse(fixture.bluetooth.closed)
  } } finally { controller.destroy() }
 }

}
