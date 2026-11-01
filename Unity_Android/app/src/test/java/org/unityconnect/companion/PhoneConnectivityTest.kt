package org.unityconnect.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.*
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Looper
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneConnectivityTest {
 private val app get() = RuntimeEnvironment.getApplication()
 private val manager get() = app.getSystemService(BluetoothManager::class.java)
 private val adapter get() = manager.adapter
 private val settings get() = app.getSharedPreferences("settings", 0)
 @Before fun prepare() {
  ShadowBuild.setManufacturer("samsung")
  shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT)
  shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
  shadowOf(app.getSystemService(WifiManager::class.java)).setWifiState(WifiManager.WIFI_STATE_ENABLED)
 }
 private fun enable() { settings.edit().putBoolean("enable.phone_connectivity", true).putBoolean("enable.samsung_routines", true).commit() }
 private fun device(address: String, name: String) = adapter.getRemoteDevice(address).also { shadowOf(it).setName(name) }
 private fun acl(action: String, device: BluetoothDevice) { app.sendBroadcast(Intent(action).putExtra(BluetoothDevice.EXTRA_DEVICE, device)); shadowOf(Looper.getMainLooper()).idle() }
 private fun await(test: () -> Boolean) {
  val end = System.nanoTime() + 5_000_000_000
  while (!test()) { check(System.nanoTime() < end) { "Timed out" }; shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
 }
 private fun devices(state: JSONObject): List<JSONObject> = state.getJSONArray("devices").let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
 @Test fun reportsStartOffAndMissingBluetoothPermissionDoesNotHideHotspotState() {
  val controller = Robolectric.buildService(CompanionService::class.java).create(); val service = controller.get()
  try {
   assertEquals("disabled", service.features.phoneConnectivity.capability().getString("status"))
   assertTrue(devices(service.features.phoneConnectivity.snapshot()).isEmpty())
   val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
   try { assertFalse(activity.get().findViewById<Switch>(R.id.feature_phone_connectivity).isChecked) }
   finally { activity.pause().stop().destroy() }
   enable(); TestConnection(service).use {
    shadowOf(Looper.getMainLooper()).idle()
    app.sendBroadcast(Intent(PhoneConnectivity.HOTSPOT_STATE_CHANGED).putExtra("wifi_state", 13)); shadowOf(Looper.getMainLooper()).idle()
    shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    val state = service.features.phoneConnectivity.snapshot()
    assertTrue(state.getBoolean("hotspot_enabled")); assertTrue(state.isNull("bluetooth_enabled"))
    assertEquals("permission_required", state.getString("bluetooth_status")); assertTrue(devices(state).isEmpty())
   }
  } finally { controller.destroy(); shadowOf(Looper.getMainLooper()).idle() }
 }
 @Test fun preexistingProfilesAndBleDevicesAreDeduplicatedAndSentToTheLaptop() {
  val controller = Robolectric.buildService(CompanionService::class.java).create(); val service = controller.get(); enable()
  val headphones = device("11:22:33:44:55:66", "Headphones")
  val pairedOnly = device("22:33:44:55:66:77", "Paired but disconnected")
  val proxy = Shadow.newInstanceOf(BluetoothA2dp::class.java)
  shadowOf(proxy).addDevice(headphones, BluetoothProfile.STATE_CONNECTED)
  shadowOf(adapter).setProfileProxy(BluetoothProfile.A2DP, proxy)
  shadowOf(adapter).setBondedDevices(setOf(headphones, pairedOnly))
  shadowOf(manager).addDevice(BluetoothProfile.GATT, BluetoothProfile.STATE_CONNECTED, headphones)
  try { TestConnection(service, true).use { link ->
   shadowOf(Looper.getMainLooper()).idle()
   await { devices(service.features.phoneConnectivity.snapshot()).any { it.getString("name") == "Headphones" } }
   val state = service.features.phoneConnectivity.snapshot(); val entries = devices(state)
   assertEquals(1, entries.count { it.getString("address") == headphones.address })
   assertTrue(entries.none { it.getString("address") == pairedOnly.address })
   val connected = entries.single { it.getString("address") == headphones.address }
   assertTrue(connected.getJSONArray("profiles").toString().contains("Audio")); assertTrue(connected.getJSONArray("profiles").toString().contains("BLE"))
   assertFalse(state.getBoolean("complete"))
   assertTrue(entries.any { it.getString("address") == "AA:BB:CC:DD:EE:FF" && it.getJSONArray("profiles").toString().contains("Unity") })
   service.features.phoneConnectivity.publish(true)
   val event = link.bluetooth.next { it.optString("type") == "event" && it.optString("feature") == "phone_connectivity" &&
    devices(it.getJSONObject("data")).any { entry -> entry.getString("address") == headphones.address && entry.getJSONArray("profiles").toString().contains("Audio") } }
   assertTrue(event.getJSONObject("data").isNull("hotspot_enabled"))
  } } finally { controller.destroy(); shadowOf(Looper.getMainLooper()).idle() }
  assertFalse(shadowOf(adapter).hasActiveProfileProxy(BluetoothProfile.A2DP))
 }
 @Test fun eachDisconnectUsesTheSelectedConnectedDeviceAndCannotRetargetAStaleRow() {
  val controller = Robolectric.buildService(CompanionService::class.java).create(); val service = controller.get(); enable()
  val first = device("11:22:33:44:55:66", "Headphones"); val second = device("22:33:44:55:66:77", "Speaker")
  try { TestConnection(service).use {
   shadowOf(Looper.getMainLooper()).idle()
   acl(BluetoothDevice.ACTION_ACL_CONNECTED, first); acl(BluetoothDevice.ACTION_ACL_CONNECTED, second)
   assertEquals(2, devices(service.features.phoneConnectivity.snapshot()).size)
   val routines = service.features.modules.getValue("samsung_routines")
   val result = routines.command("disconnect_bluetooth_device", JSONObject().put("address", first.address.lowercase()))!!
   assertEquals("UNITY_BLUETOOTH_DISCONNECT_112233445566", result.getString("keyword")); assertFalse(result.getBoolean("confirmed"))
   val notifications = app.getSystemService(NotificationManager::class.java)
   val posted = notifications.activeNotifications.single { it.tag == SamsungRoutines.CHANNEL }
   assertEquals(result.getString("keyword"), posted.notification.extras.getString(Notification.EXTRA_TITLE))
   assertEquals("Trigger submission cannot fabricate disconnection", 2, devices(service.features.phoneConnectivity.snapshot()).size)
   val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
   try {
    activity.get().findViewById<android.view.View>(R.id.phone_bluetooth_devices).performClick()
    val text = shadowOf(ShadowAlertDialog.getLatestAlertDialog()).message.toString()
    assertTrue(text.contains("Headphones")); assertTrue(text.contains("UNITY_BLUETOOTH_DISCONNECT_112233445566"))
    ShadowAlertDialog.getLatestAlertDialog().dismiss()
   } finally { activity.pause().stop().destroy() }
   acl(BluetoothDevice.ACTION_ACL_DISCONNECTED, first)
   assertEquals(listOf(second.address), devices(service.features.phoneConnectivity.snapshot()).map { it.getString("address") })
   assertThrows(IllegalStateException::class.java) { routines.command("disconnect_bluetooth_device", JSONObject().put("address", first.address)) }
   assertEquals(posted.id, notifications.activeNotifications.single { it.tag == SamsungRoutines.CHANNEL }.id)
   shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
   assertTrue(devices(service.features.phoneConnectivity.snapshot()).isEmpty())
   assertThrows(IllegalStateException::class.java) { routines.command("disconnect_bluetooth_device", JSONObject().put("address", second.address)) }
  } } finally { controller.destroy(); shadowOf(Looper.getMainLooper()).idle() }
 }
 @Test fun hotspotReportsAreIndependentOfWifiAndOnlyChangeAfterAnAccessPointBroadcast() {
  val controller = Robolectric.buildService(CompanionService::class.java).create(); val service = controller.get(); enable()
  try { TestConnection(service).use { link ->
   shadowOf(Looper.getMainLooper()).idle()
   val feature = service.features.modules.getValue("samsung_routines")
   assertTrue(service.features.phoneConnectivity.snapshot().isNull("hotspot_enabled"))
   app.sendBroadcast(Intent(PhoneConnectivity.HOTSPOT_STATE_CHANGED).putExtra("wifi_state", 11)); shadowOf(Looper.getMainLooper()).idle()
   val on = feature.command("hotspot_on", JSONObject())!!
   assertEquals("UNITY_HOTSPOT_ON", on.getString("keyword")); assertFalse(on.getBoolean("confirmed"))
   assertFalse(service.features.phoneConnectivity.snapshot().getBoolean("hotspot_enabled"))
   app.sendBroadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION).putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED)); shadowOf(Looper.getMainLooper()).idle()
   assertFalse(service.features.phoneConnectivity.snapshot().getBoolean("hotspot_enabled"))
   app.sendBroadcast(Intent(PhoneConnectivity.HOTSPOT_STATE_CHANGED).putExtra("wifi_state", 13)); shadowOf(Looper.getMainLooper()).idle()
   val event = link.lan.next { it.optString("feature") == "phone_connectivity" && it.getJSONObject("data").optBoolean("hotspot_enabled", false) }
   assertTrue(event.getJSONObject("data").getBoolean("hotspot_enabled"))
   assertEquals("UNITY_HOTSPOT_OFF", feature.command("hotspot_off", JSONObject())!!.getString("keyword"))
   assertTrue(service.features.phoneConnectivity.snapshot().getBoolean("hotspot_enabled"))
   app.sendBroadcast(Intent(PhoneConnectivity.HOTSPOT_STATE_CHANGED).putExtra("wifi_state", 12)); shadowOf(Looper.getMainLooper()).idle()
   assertTrue(service.features.phoneConnectivity.snapshot().isNull("hotspot_enabled"))
   settings.edit().putBoolean("enable.phone_connectivity", false).commit(); service.features.publish(); shadowOf(Looper.getMainLooper()).idle()
   assertTrue(devices(service.features.phoneConnectivity.snapshot()).isEmpty())
   assertThrows(IllegalStateException::class.java) { service.features.phoneConnectivity.command("refresh", JSONObject()) }
  } } finally { controller.destroy(); shadowOf(Looper.getMainLooper()).idle() }
 }
}
