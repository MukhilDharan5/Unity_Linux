package org.unityconnect.companion

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import org.json.JSONObject
import java.net.InetAddress
import java.net.NetworkInterface

internal data class LanAddress(val host: String, val port: Int)
internal data class LanRoute(val network: Network? = null, val localAddress: InetAddress? = null, val prefixLength: Int = 0)
internal data class LocalInterfaceAddress(val address: InetAddress, val prefixLength: Int)

/** Endpoints arrive only after the paired peer has passed pinned TLS and device proof. */
internal class NetworkPaths {
 @Volatile private var learned: List<LanAddress> = emptyList()
 fun receive(data: JSONObject): Boolean {
  require(data.optInt("version") == 1 && data.optString("origin") == "desktop") { "Invalid network update" }
  require(data.optBoolean("encrypted") && data.optString("tls") == "TLSv1.3") { "Encrypted Unity connection required" }
  val input = data.getJSONArray("lan_endpoints")
  require(input.length() <= 16) { "Too many network addresses" }
  val values = (0 until input.length()).map { index ->
   val entry = input.getJSONObject(index)
   val host = entry.getString("host"); val port = entry.getInt("port")
   require(numericHost(host) && port in 1..65535) { "Invalid network endpoint" }
   val address = InetAddress.getByName(host)
   require(!address.isAnyLocalAddress && !address.isMulticastAddress && !address.isLoopbackAddress) { "Invalid network endpoint" }
   LanAddress(host,port)
  }.distinct()
  if (values == learned) return false
  learned = values; return true
 }
 fun candidates(host: String, port: Int): List<LanAddress> {
  val saved = if (host.isBlank()) emptyList() else listOf(LanAddress(host,port))
  val compatible = if (host.isNotBlank() && port == 17161) (1764 downTo 1761).map { LanAddress(host,it) } else emptyList()
  // Keep every bounded authenticated endpoint until routes have been ranked.
  // A new hotspot address must not be cut off behind old/VPN/VM addresses.
  return (learned + saved + compatible).distinct()
 }
 fun advertises(host: String, port: Int): Boolean = numericHost(host) && learned.any {
  it.port==port && InetAddress.getByName(it.host)==InetAddress.getByName(host)
 }
 companion object {
  fun numericHost(host: String): Boolean {
   if (host.length !in 2..64) return false
   if (host.contains(':')) return host.matches(Regex("[0-9a-fA-F:]+")) && runCatching { InetAddress.getByName(host) }.isSuccess
   val parts=host.split('.')
   return parts.size==4 && parts.all { it.isNotEmpty() && it.length<=3 && it.all { c -> c in '0'..'9' } && it.toInt() in 0..255 }
  }
 }
}

internal object LanNetwork {
 fun select(context: Context, host: String): Network? = route(context,host).network
 fun route(context: Context, host: String): LanRoute = route(context,host,::localAddresses)
 internal fun route(context: Context, host: String, interfaces: (Set<String>) -> List<LocalInterfaceAddress>): LanRoute {
  val manager=context.getSystemService(ConnectivityManager::class.java)
  val active=manager.activeNetwork
  // Respect an existing VPN instead of quietly bypassing it to reach the LAN.
  if (active!=null && manager.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true) return LanRoute(active)
  val networks=manager.allNetworks.filter { network -> manager.getNetworkCapabilities(network)?.let {
   !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
    Build.VERSION.SDK_INT>=35 && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK))
  }==true }
  if (NetworkPaths.numericHost(host)) {
   val address=InetAddress.getByName(host)
   val routed=networks.flatMap { network -> manager.getLinkProperties(network)?.routes.orEmpty().filter {
    (Build.VERSION.SDK_INT<33 || it.type==RouteInfo.RTN_UNICAST) && it.destination.prefixLength>0 && it.destination.contains(address)
   }.map { LanRoute(network,prefixLength=it.destination.prefixLength) } }.maxByOrNull { it.prefixLength }
   if (routed!=null) return routed
   // Some Android/OEM releases expose the hotspot interface but no Network for
   // its downstream link. Bind that local source, never the unrelated upstream
   // Wi-Fi/cellular Network. Known upstream and VPN interfaces are excluded.
   val known=manager.allNetworks.mapNotNull { manager.getLinkProperties(it)?.interfaceName }.toSet()
   interfaces(known).filter { sameSubnet(address,it.address,it.prefixLength) }
    .maxByOrNull { it.prefixLength }?.let { return LanRoute(localAddress=it.address,prefixLength=it.prefixLength) }
  }
  val upstream=networks.filter { network -> Build.VERSION.SDK_INT<35 || manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)!=true }
  return LanRoute(upstream.firstOrNull { it==active } ?: upstream.firstOrNull())
 }
 internal fun sameSubnet(peer: InetAddress, local: InetAddress, prefix: Int): Boolean {
  val a=peer.address;val b=local.address
  if(a.size!=b.size || prefix !in 1..a.size*8 || peer.isLoopbackAddress || peer.isMulticastAddress || peer.isAnyLocalAddress) return false
  return a.indices.all { index ->
   val bits=(prefix-index*8).coerceIn(0,8)
   val mask=if(bits==0) 0 else (0xff shl (8-bits)) and 0xff
   (a[index].toInt() and mask)==(b[index].toInt() and mask)
  }
 }
 private fun localAddresses(known: Set<String>): List<LocalInterfaceAddress> = runCatching {
  NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter {
   it.name !in known && it.isUp && !it.isLoopback && !it.isPointToPoint
  }.flatMap { network -> network.interfaceAddresses.mapNotNull {
   val address=it.address
   if(address==null || address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) null
   else LocalInterfaceAddress(address,it.networkPrefixLength.toInt())
  } }
 }.getOrDefault(emptyList())
 internal fun endpoints(context: Context, addresses: List<LanAddress>): List<Pair<LanAddress,LanRoute>> {
  return addresses.map { it to route(context,it.host) }.sortedByDescending { it.second.prefixLength }.take(12)
 }
}
