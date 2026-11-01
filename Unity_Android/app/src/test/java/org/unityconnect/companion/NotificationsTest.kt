package org.unityconnect.companion

import org.junit.Assert.*
import org.junit.Test

class NotificationsTest {
 @Test fun longUnicodeNotificationKeysRemainBoundedDistinctAndStable() {
  val prefix = "தமிழ்😀".repeat(256)
  val one = notificationId(prefix + "one")
  val two = notificationId(prefix + "two")
  assertTrue(one.toByteArray(Charsets.UTF_8).size <= 512)
  assertEquals(one, notificationId(prefix + "one"))
  assertNotEquals(one, two)
  assertFalse(notificationId("tag\u0000value").contains('\u0000'))
 }
}
