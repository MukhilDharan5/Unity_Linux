package org.unityconnect.companion

import android.content.Context
import android.Manifest
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.RouteInfo
import android.net.IpPrefix
import android.net.wifi.WifiManager
import org.robolectric.shadows.ShadowWifiInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class PublicWifiTest {
 private val context get() = RuntimeEnvironment.getApplication()
 private val manager get() = context.getSystemService(ConnectivityManager::class.java)
 @Before fun resetNetworks() {
  shadowOf(manager).clearAllNetworks()
  context.getSharedPreferences("network_trust",Context.MODE_PRIVATE).edit().clear().commit()
  shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
 }
 private fun wifiName(ssid:String,bssid:String="00:11:22:33:44:55") {
  val info=ShadowWifiInfo.newInstance()
  shadowOf(info).setSSID("\"$ssid\"");shadowOf(info).setBSSID(bssid)
  shadowOf(context.getSystemService(WifiManager::class.java)).setConnectionInfo(info)
 }
 @Suppress("DEPRECATION")
 private fun network(id: Int, transport: Int, active: Boolean = false): android.net.Network {
  val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,id,0,true,true)
  if (active) shadowOf(manager).setActiveNetworkInfo(info)
  val network = ShadowNetwork.newInstance(id)
  shadowOf(manager).addNetwork(network,info)
  shadowOf(manager).setNetworkCapabilities(network,ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addTransportType(transport) })
  return network
 }
 private fun update(vararg hosts: String) = JSONObject().put("version",1).put("origin","desktop")
  .put("encrypted",true).put("tls","TLSv1.3").put("lan_endpoints",JSONArray().apply {
   hosts.forEach { put(JSONObject().put("host",it).put("port",1764)) }
  })
 @Test fun learnedCampusAddressesReplaceStaleManualAddressWithoutAcceptingDowngradesOrDns() {
  val paths = NetworkPaths()
  assertTrue(paths.receive(update("100.64.32.8","2001:db8:42::1")))
  assertEquals(LanAddress("100.64.32.8",1764),paths.candidates("192.168.1.10",17161).first())
  assertFalse(paths.receive(update("100.64.32.8","2001:db8:42::1")))
  for (bad in listOf(update("evil.example"),update("127.0.0.1"),update("::"),update("224.0.0.1"),
   update("100.64.32.8").put("encrypted",false),update("100.64.32.8").put("tls","TLSv1.2"),
   update("100.64.32.8").put("lan_endpoints",JSONArray().put(JSONObject().put("host","100.64.32.8").put("port",0))))) {
   assertThrows(IllegalArgumentException::class.java) { paths.receive(bad) }
   assertEquals("100.64.32.8",paths.candidates("",17161).first().host)
  }
 }
 @Test fun localWifiIsUsedWhenCaptivePortalLeavesMobileDataAsDefaultAndVpnIsRespected() {
  val mobile = network(0,NetworkCapabilities.TRANSPORT_CELLULAR,true)
  val wifi = network(1,NetworkCapabilities.TRANSPORT_WIFI)
  shadowOf(manager).setLinkProperties(wifi,LinkProperties().apply { interfaceName = "wlan0"; addRoute(RouteInfo::class.java.getDeclaredConstructor(IpPrefix::class.java, java.net.InetAddress::class.java, String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(IpPrefix(java.net.InetAddress.getByName("100.64.32.0"),24),null,"wlan0",RouteInfo.RTN_UNICAST)) })
  assertEquals(mobile,manager.activeNetwork)
  assertEquals(wifi,LanNetwork.select(context,"100.64.32.8"))
  assertEquals(wifi,LanNetwork.route(context,"192.168.1.10") { emptyList() }.network)
  val vpn = network(17,NetworkCapabilities.TRANSPORT_VPN,true)
  assertEquals(vpn,LanNetwork.select(context,"100.64.32.8"))
  assertNull(manager.boundNetworkForProcess)
 }
 @Test fun unknownWifiAsksOncePerConnectionAndAnswersNeverCarryToAnotherNetwork() {
  val wifi = network(1,NetworkCapabilities.TRANSPORT_WIFI)
  val privacy = NetworkPrivacy(context)
  val first = privacy.shouldAsk()!!
  assertEquals("unknown",first.privacy)
  privacy.defer(first.key); assertNull(privacy.shouldAsk())
  privacy.choose(first.key,"private"); assertEquals("private",privacy.current()!!.privacy)
  shadowOf(manager).removeNetwork(wifi)
  network(2,NetworkCapabilities.TRANSPORT_WIFI)
  assertNotNull(privacy.shouldAsk())
  assertThrows(IllegalStateException::class.java) { privacy.choose(first.key,"private") }
  assertEquals("unknown",privacy.current()!!.privacy)
 }
 @Test fun captivePortalIsPublicEvenBeforeSignInAndEncryptionCannotBeTurnedOff() {
  val wifi = network(1,NetworkCapabilities.TRANSPORT_WIFI)
  shadowOf(manager).setNetworkCapabilities(wifi,ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI); shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) })
  val privacy = NetworkPrivacy(context)
  assertEquals("public",privacy.current()!!.privacy)
  assertNull(privacy.shouldAsk())
  assertTrue(privacy.current()!!.reason.contains("encrypted"))
 }
 @Test fun bothTrustChoicesSurviveServiceRestartsReconnectsAndAccessPointRoaming() {
  shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
  wifiName("Home Wi-Fi")
  var wifi=network(11,NetworkCapabilities.TRANSPORT_WIFI)
  val privacy=NetworkPrivacy(context)
  privacy.choose(privacy.current()!!.key,"private")
  assertTrue(privacy.current()!!.remembered)
  shadowOf(manager).removeNetwork(wifi)
  wifiName("Home Wi-Fi","11:22:33:44:55:66")
  wifi=network(12,NetworkCapabilities.TRANSPORT_WIFI)
  assertEquals("private",NetworkPrivacy(context).current()!!.privacy)
  assertNull(NetworkPrivacy(context).shouldAsk())
  shadowOf(manager).removeNetwork(wifi)
  wifiName("Coffee Wi-Fi");wifi=network(13,NetworkCapabilities.TRANSPORT_WIFI)
  val cafe=NetworkPrivacy(context)
  assertNotNull(cafe.shouldAsk());cafe.choose(cafe.current()!!.key,"public")
  shadowOf(manager).removeNetwork(wifi)
  network(14,NetworkCapabilities.TRANSPORT_WIFI)
  assertEquals("public",NetworkPrivacy(context).current()!!.privacy)
  assertNull(NetworkPrivacy(context).shouldAsk())
  val data=context.getSharedPreferences("network_trust",Context.MODE_PRIVATE).all.toString()
  assertFalse(data.contains("Home Wi-Fi"));assertFalse(data.contains("Coffee Wi-Fi"))
  assertFalse(data.contains("11:22:33:44:55:66"))
 }
 @Test fun anotherWifiCannotInheritTrustFromAReusedNetworkHandleAndReviewCanChangeASavedAnswer() {
  shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
  wifiName("Home");network(1,NetworkCapabilities.TRANSPORT_WIFI)
  val privacy=NetworkPrivacy(context);privacy.choose(privacy.current()!!.key,"private")
  wifiName("Guest")
  assertEquals("unknown",privacy.current()!!.privacy);assertNotNull(privacy.shouldAsk())
  privacy.choose(privacy.current()!!.key,"public")
  wifiName("Home")
  assertEquals("private",privacy.current()!!.privacy)
  privacy.choose(privacy.current()!!.key,"public")
  assertEquals("public",NetworkPrivacy(context).current()!!.privacy)
 }
 @Test fun grantingWifiIdentityPermissionSavesTheCurrentChoiceWithoutRepeatingTheQuestion() {
  wifiName("Home");network(1,NetworkCapabilities.TRANSPORT_WIFI)
  val privacy=NetworkPrivacy(context);privacy.choose(privacy.current()!!.key,"private")
  assertFalse(privacy.current()!!.remembered)
  assertTrue(context.getSharedPreferences("network_trust",Context.MODE_PRIVATE).all.isEmpty())
  shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
  assertTrue(privacy.current()!!.remembered)
  assertEquals("private",NetworkPrivacy(context).current()!!.privacy)
  wifiName("<unknown ssid>")
  assertEquals("unknown",NetworkPrivacy(context).current()!!.privacy)
  assertNotNull(NetworkPrivacy(context).shouldAsk())
 }
 @Test fun authenticatedBluetoothUpdateStartsLanAtNewAddressAndKeepsBluetoothAlive() {
  val controller = Robolectric.buildService(CompanionService::class.java).create()
  try { TestConnection(controller.get(),true).use { fixture ->
   fixture.rejectLan = true; fixture.lan.disconnect()
   fixture.await { controller.get().connection.transportLabel == "Bluetooth" }
   fixture.rejectLan = false
   fixture.bluetooth.send(JSONObject().put("type","event").put("feature","connectivity").put("data",update("100.64.32.8")))
   fixture.await { controller.get().connection.lanEndpointLabel == "100.64.32.8:1764" }
   assertFalse(fixture.bluetooth.closed)
   assertEquals(1,fixture.attempts.get())
   assertEquals("Bluetooth + Wi-Fi / LAN",controller.get().connection.transportLabel)
  } } finally { controller.destroy() }
 }
 @Test fun bluetoothPairingCanLearnWifiWithoutAnyManualHost() {
  val controller = Robolectric.buildService(CompanionService::class.java).create()
  try { TestConnection(controller.get(),true,savedHost="").use { fixture ->
   assertEquals("Bluetooth",controller.get().connection.transportLabel)
   fixture.bluetooth.send(JSONObject().put("type","event").put("feature","connectivity").put("data",update("100.64.32.8")))
   fixture.await { controller.get().connection.lanEndpointLabel == "100.64.32.8:1764" }
   assertFalse(fixture.bluetooth.closed)
   assertEquals("Bluetooth + Wi-Fi / LAN",controller.get().connection.transportLabel)
  } } finally { controller.destroy() }
 }
 @Test fun unknownNetworkShowsAnActualChoiceWithPublicSelectedByDefault() {
  network(1,NetworkCapabilities.TRANSPORT_WIFI)
  val service = Robolectric.buildService(CompanionService::class.java).create()
  val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
  try {
   val dialog = ShadowAlertDialog.getLatestAlertDialog()
   assertNotNull(dialog)
   assertTrue(dialog.isShowing)
   assertEquals(2,dialog.listView.adapter.count)
   assertEquals(0,dialog.listView.checkedItemPosition)
   dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
   shadowOf(android.os.Looper.getMainLooper()).idle()
   assertEquals("public",service.get().networkPrivacy.current()!!.privacy)
   assertNull(service.get().networkPrivacy.shouldAsk())
  } finally { activity.pause().stop().destroy(); service.destroy() }
 }
}
