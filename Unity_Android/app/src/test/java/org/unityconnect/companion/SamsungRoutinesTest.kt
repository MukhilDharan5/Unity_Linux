package org.unityconnect.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.Switch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SamsungRoutinesTest {
 private val app get() = RuntimeEnvironment.getApplication()
 private val manager get() = app.getSystemService(NotificationManager::class.java)
 private fun enable() { app.getSharedPreferences("settings", 0).edit().putBoolean("enable.samsung_routines", true).commit() }
 private fun notice() = manager.activeNotifications.single { it.tag == SamsungRoutines.CHANNEL }
 private fun await(test: () -> Boolean) {
  val end = System.nanoTime() + 5_000_000_000
  while (!test()) { check(System.nanoTime() < end) { "Timed out" }; shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
 }
 @Before fun samsung() {
  ShadowBuild.setManufacturer("samsung")
  shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
 }
 @Test fun fixedKeywordsCreateFreshBoundedNotificationsAndNeverClaimExecution() {
  val controller = Robolectric.buildService(CompanionService::class.java).create(); val service = controller.get(); enable()
  try {
   val feature = service.features.modules.getValue("samsung_routines")
   val connectionIntent = PendingIntent.getActivity(service, 0, Intent(service, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
   manager.notify(1, Notification.Builder(service, "unity.connection").setSmallIcon(R.drawable.ic_unity_symbolic)
    .setContentTitle("Connected").setContentIntent(connectionIntent).build())
   var previous = -1
   for ((action, keyword) in listOf("hotspot_on" to "UNITY_HOTSPOT_ON", "hotspot_off" to "UNITY_HOTSPOT_OFF",
     "disconnect_bluetooth" to "UNITY_BLUETOOTH_DISCONNECT", "disconnect_bluetooth" to "UNITY_BLUETOOTH_DISCONNECT")) {
    val result = feature.command(action, JSONObject())!!
    assertEquals("trigger_sent", result.getString("state")); assertEquals(keyword, result.getString("keyword")); assertFalse(result.getBoolean("confirmed"))
    val posted = notice(); assertNotEquals(previous, posted.id); previous = posted.id
    assertEquals(keyword, posted.notification.extras.getString(Notification.EXTRA_TITLE))
    assertTrue(posted.notification.extras.getString(Notification.EXTRA_TEXT)!!.startsWith(keyword))
    assertEquals(SamsungRoutines.CHANNEL, posted.notification.channelId)
    assertEquals(60_000L, posted.notification.timeoutAfter)
    assertEquals(0, posted.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    assertEquals(2, manager.activeNotifications.size)
    assertTrue(posted.notification.contentIntent.isImmutable)
    assertEquals("samsung_routines", shadowOf(posted.notification.contentIntent).savedIntent.getStringExtra("phone_action"))
   }
   assertEquals("Connected", manager.activeNotifications.single { it.tag == null && it.id == 1 }.notification.extras.getString(Notification.EXTRA_TITLE))
   assertNull(shadowOf(connectionIntent).savedIntent.getStringExtra("phone_action"))
   val id = notice().id
   for (action in listOf("toggle", "connect_bluetooth", "post")) assertThrows(IllegalStateException::class.java) { feature.command(action, JSONObject()) }
   assertThrows(IllegalArgumentException::class.java) { feature.command("disconnect_bluetooth", JSONObject().put("address", "AA:BB:CC:DD:EE:FF")) }
   assertEquals(id, notice().id)
  } finally { controller.destroy() }
 }
 @Test fun localOptInAndNotificationPermissionAreRequiredForEveryCommand() {
  val controller = Robolectric.buildService(CompanionService::class.java).create(); val feature = controller.get().features.modules.getValue("samsung_routines")
  try {
   assertEquals("disabled", feature.capability().getString("status"))
   assertThrows(IllegalStateException::class.java) { feature.command("hotspot_on", JSONObject()) }
   enable(); assertEquals("available", feature.capability().getString("status"))
   shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
   assertEquals("permission_required", feature.capability().getString("status"))
   assertThrows(IllegalStateException::class.java) { feature.command("hotspot_on", JSONObject()) }
   shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
   feature.command("hotspot_on", JSONObject()); val id = notice().id
   app.getSharedPreferences("settings", 0).edit().putBoolean("enable.samsung_routines", false).commit()
   assertThrows(IllegalStateException::class.java) { feature.command("hotspot_off", JSONObject()) }
   assertEquals(id, notice().id)
  } finally { controller.destroy() }
 }
 @Test fun blockedAppOrChannelAndNonSamsungDevicesCannotSendTriggers() {
  val controller = Robolectric.buildService(CompanionService::class.java).create(); val feature = controller.get().features.modules.getValue("samsung_routines"); enable()
  try {
   shadowOf(manager).setNotificationsEnabled(false)
   assertEquals("permission_required", feature.capability().getString("status"))
   assertThrows(IllegalStateException::class.java) { feature.command("hotspot_on", JSONObject()) }
   shadowOf(manager).setNotificationsEnabled(true)
   manager.createNotificationChannel(NotificationChannel(SamsungRoutines.CHANNEL, "Blocked", NotificationManager.IMPORTANCE_NONE))
   assertEquals("permission_required", feature.capability().getString("status"))
   assertThrows(IllegalStateException::class.java) { feature.command("hotspot_on", JSONObject()) }
   ShadowBuild.setManufacturer("other")
   assertEquals("unsupported", feature.capability().getString("status"))
   assertThrows(IllegalStateException::class.java) { feature.command("hotspot_on", JSONObject()) }
   assertTrue(manager.activeNotifications.isEmpty())
  } finally { controller.destroy() }
 }
 @Test fun authenticatedReplayAcrossBothLinksDoesNotRetriggerARoutine() {
  val controller = Robolectric.buildService(CompanionService::class.java).create(); val service = controller.get(); enable()
  try { TestConnection(service, true).use { link ->
   val id = "ab".repeat(16)
   val command = JSONObject().put("type", "command").put("id", id).put("feature", "samsung_routines")
    .put("action", "hotspot_on").put("data", JSONObject())
   link.lan.send(command)
   await { manager.activeNotifications.any { it.tag == SamsungRoutines.CHANNEL } }
   val first = notice().id
   assertTrue(link.lan.next { it.optString("type") == "result" && it.optString("id") == id }.getBoolean("ok"))
   link.bluetooth.send(command)
   val replay = link.bluetooth.next { it.optString("type") == "result" && it.optString("id") == id }
   assertTrue(replay.getBoolean("ok")); assertFalse(replay.getJSONObject("data").getBoolean("confirmed")); assertEquals(first, notice().id)
   link.bluetooth.send(JSONObject(command.toString()).put("action", "hotspot_off"))
   await { link.bluetooth.closed }; assertEquals(first, notice().id)
  } } finally { controller.destroy(); shadowOf(Looper.getMainLooper()).idle() }
 }
 @Test fun phoneSetupListsTheExactKeywordsAndKeepsTheBluetoothTargetOnThePhone() {
  val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); val activity = controller.get()
  try {
   activity.findViewById<View>(R.id.nav_features).performClick()
   assertFalse(activity.findViewById<Switch>(R.id.feature_samsung_routines).isChecked)
   activity.findViewById<View>(R.id.setup_samsung_routines).performClick()
   val dialog = ShadowAlertDialog.getLatestAlertDialog()
   val text = shadowOf(dialog).message.toString()
   listOf("UNITY_HOTSPOT_ON", "UNITY_HOTSPOT_OFF", "UNITY_BLUETOOTH_DISCONNECT", "Unity Preview", "choose the specific device", "cannot confirm").forEach { assertTrue(text, text.contains(it)) }
   dialog.dismiss()
  } finally { controller.pause().stop().destroy() }
 }
}
