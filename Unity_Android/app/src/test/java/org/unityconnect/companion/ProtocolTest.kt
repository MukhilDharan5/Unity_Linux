package org.unityconnect.companion
import org.junit.Assert.*
import org.junit.Test
class ProtocolTest {
 @Test fun deviceAndNonceBoundProof() {
  val id = "0123456789abcdef0123456789abcdef"
  assertEquals("132b32a231e9d461c7946c07250e35cc38aaf226f6b9a29b2c58268b8b727272", Protocol.proof("01".repeat(32), id, "02".repeat(32)))
  assertNotEquals(Protocol.proof("01".repeat(32), id, "02".repeat(32)), Protocol.proof("01".repeat(32), id, "03".repeat(32)))
 }
 @Test fun pathsCannotEscapeQuarantine() { for (s in listOf("../a", "/a", ".", "..", "a\\b", "\n")) assertFalse(Protocol.safeName(s)); assertTrue(Protocol.safeName("photo.jpg")) }
 @Test fun unicodeNeverExceedsWireLimits() { val value = "📱".repeat(1000); val clipped = Protocol.text(value, 513); assertTrue(clipped.toByteArray(Charsets.UTF_8).size <= 513); assertFalse(clipped.contains('\uFFFD')); assertFalse(Protocol.safeName("界".repeat(100))) }
 @Test fun identitiesAreConstrained() { assertTrue(Protocol.validId("01".repeat(16))); assertFalse(Protocol.validId("../phone")); assertFalse(Protocol.validId("01".repeat(17))) }
}
