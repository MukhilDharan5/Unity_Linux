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
}
