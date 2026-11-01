package org.unityconnect.companion

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.RouteInfo
import java.net.InetAddress
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
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class HotspotNetworkTest {
 private val context get() = RuntimeEnvironment.getApplication()
 private val manager get() = context.getSystemService(ConnectivityManager::class.java)
 @Before fun reset() { shadowOf(manager).clearAllNetworks() }
 @Suppress("DEPRECATION")
 private fun network(id:Int, transport:Int?=null, active:Boolean=false, hotspot:Boolean=false):Network {
  val info=ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,id,0,true,true)
  if(active) shadowOf(manager).setActiveNetworkInfo(info)
  val network=ShadowNetwork.newInstance(id)
  shadowOf(manager).addNetwork(network,info)
  shadowOf(manager).setNetworkCapabilities(network,ShadowNetworkCapabilities.newInstance().also {
   if(transport!=null) shadowOf(it).addTransportType(transport)
   if(hotspot) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
  })
  return network
 }
 private fun route(network:Network, interfaceName:String, host:String, prefix:Int, type:Int=RouteInfo.RTN_UNICAST) {
  val route=RouteInfo::class.java.getDeclaredConstructor(IpPrefix::class.java,InetAddress::class.java,String::class.java,Int::class.javaPrimitiveType)
   .apply { isAccessible=true }.newInstance(IpPrefix(InetAddress.getByName(host),prefix),InetAddress.getByName("0.0.0.0"),interfaceName,type)
  shadowOf(manager).setLinkProperties(network,LinkProperties().apply { this.interfaceName=interfaceName;addRoute(route) })
 }
 @Test fun ownHotspotIsSelectedWithoutInternetOrAClientWifiTransport() {
  network(0,NetworkCapabilities.TRANSPORT_CELLULAR,true)
  val hotspot=network(2,hotspot=true)
  route(hotspot,"ap0","192.168.182.0",24)
  assertFalse(manager.getNetworkCapabilities(hotspot)!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
  val selected=LanNetwork.route(context,"192.168.182.42") { error("A public hotspot Network is available") }
  assertEquals(hotspot,selected.network);assertEquals(24,selected.prefixLength);assertNull(selected.localAddress)
  assertNull(manager.boundNetworkForProcess)
 }
 @Test fun hotspotRouteWinsOverUnrelatedUpstreamWifiAndAChangedSubnetIsUsed() {
  val wifi=network(1,NetworkCapabilities.TRANSPORT_WIFI,true);route(wifi,"wlan0","192.168.1.0",24)
  val hotspot=network(2,hotspot=true);route(hotspot,"ap0","192.168.182.0",24)
  assertEquals(hotspot,LanNetwork.select(context,"192.168.182.42"))
  route(hotspot,"ap0","192.168.221.0",24)
  assertEquals(hotspot,LanNetwork.select(context,"192.168.221.42"))
  assertEquals(wifi,LanNetwork.select(context,"192.168.1.38"))
 }
 @Test @Config(sdk=[29,34]) fun olderPhonesBindTheExposedHotspotSourceRatherThanUpstreamWifi() {
  val mobile=network(0,NetworkCapabilities.TRANSPORT_CELLULAR,true);route(mobile,"rmnet0","100.64.0.0",24)
  val wifi=network(1,NetworkCapabilities.TRANSPORT_WIFI);route(wifi,"wlan0","192.168.1.0",24)
  val source=InetAddress.getByName("192.168.182.1")
  val selected=LanNetwork.route(context,"192.168.182.42") { known ->
   assertTrue(known.containsAll(listOf("rmnet0","wlan0")))
   listOf(LocalInterfaceAddress(source,24))
  }
  assertNull(selected.network);assertEquals(source,selected.localAddress);assertEquals(24,selected.prefixLength)
 }
 @Test fun vpnRemainsInChargeOfHotspotConnectionsWithoutSourceBinding() {
  val hotspot=network(2,hotspot=true);route(hotspot,"ap0","192.168.182.0",24)
  val vpn=network(17,NetworkCapabilities.TRANSPORT_VPN,true)
  val selected=LanNetwork.route(context,"192.168.182.42") { error("VPN must not be bypassed") }
  assertEquals(vpn,selected.network);assertNull(selected.localAddress);assertNull(manager.boundNetworkForProcess)
 }
 @Test fun endpointRankingKeepsTheNewHotspotAddressAfterManyStaleAddresses() {
  network(0,NetworkCapabilities.TRANSPORT_CELLULAR,true)
  val hotspot=network(2,hotspot=true);route(hotspot,"ap0","192.168.182.0",24)
  val advertised=JSONArray()
  repeat(15) { advertised.put(JSONObject().put("host","10.20.0.${it+1}").put("port",17161)) }
  advertised.put(JSONObject().put("host","192.168.182.42").put("port",1764))
  val paths=NetworkPaths();paths.receive(JSONObject().put("version",1).put("origin","desktop")
   .put("encrypted",true).put("tls","TLSv1.3").put("lan_endpoints",advertised))
  assertEquals(21,paths.candidates("192.168.1.38",17161).size)
  val selected=LanNetwork.endpoints(context,paths.candidates("192.168.1.38",17161))
  assertEquals(12,selected.size)
  assertEquals(LanAddress("192.168.182.42",1764),selected.first().first)
  assertEquals(hotspot,selected.first().second.network)
 }
 @Test fun subnetMatchingRejectsDifferentFamiliesAndDoesNotTreatDefaultRoutesAsLocal() {
  fun matches(peer:String,local:String,prefix:Int)=LanNetwork.sameSubnet(InetAddress.getByName(peer),InetAddress.getByName(local),prefix)
  assertTrue(matches("192.168.182.42","192.168.182.1",24))
  assertTrue(matches("fd12:3456::42","fd12:3456::1",64))
  assertFalse(matches("192.168.183.42","192.168.182.1",24))
  assertFalse(matches("10.1.1.1","192.168.182.1",0))
  assertFalse(matches("::1","192.168.182.1",24))
  assertFalse(matches("224.0.0.1","192.168.182.1",24))
 }
 @Test fun hotspotBroadcastRecoveryDoesNotRequirePhoneConnectivityFeatureSharing() {
  val controller=Robolectric.buildService(CompanionService::class.java).create()
  try {
   val service=controller.get()
   service.getSharedPreferences("settings",0).edit().putBoolean("enable.phone_connectivity",false).commit()
   context.sendBroadcast(Intent(PhoneConnectivity.HOTSPOT_STATE_CHANGED).putExtra("wifi_state",13))
   shadowOf(android.os.Looper.getMainLooper()).idle()
   assertFalse(service.connection.ready)
  } finally { controller.destroy() }
 }
}
