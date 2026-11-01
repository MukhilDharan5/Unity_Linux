package org.unityconnect.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.security.MessageDigest
import java.security.SecureRandom

internal data class NetworkTrust(val key: Long, val privacy: String, val reason: String, val remembered: Boolean=false, val identity: String?=null)

/** Save a device-local hash of the Wi-Fi profile, never the network handle or raw SSID. */
internal class NetworkPrivacy(private val context: Context) {
 private data class Choice(val privacy:String,var identity:String?)
 private val choices=mutableMapOf<Long,Choice>()
 private val deferred=mutableSetOf<Long>()
 private val saved=context.getSharedPreferences("network_trust",Context.MODE_PRIVATE)
 @Suppress("DEPRECATION")
 private fun identity(capabilities:NetworkCapabilities):String? {
  if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return null
  return runCatching {
   val info=(capabilities.transportInfo as? WifiInfo)?.takeIf { validSsid(it.ssid) }
    ?: context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo
   val ssid=info?.ssid?.takeIf(::validSsid)?.removeSurrounding("\"") ?: return null
   val security=if(Build.VERSION.SDK_INT>=31) info.currentSecurityType.toString() else "legacy"
   val salt=saved.getString("salt",null) ?: Protocol.hex(ByteArray(32).also { SecureRandom().nextBytes(it) }).also {
    check(saved.edit().putString("salt",it).commit()) { "Could not save Wi-Fi identity" }
   }
   Protocol.hex(MessageDigest.getInstance("SHA-256").digest("$salt\u0000$ssid\u0000$security".toByteArray(Charsets.UTF_8)))
  }.getOrNull()
 }
 private fun validSsid(value:String?)= !value.isNullOrBlank() && value.removeSurrounding("\"").let {it.isNotEmpty() && it!="<unknown ssid>"}
 private fun remember(identity:String,value:String) { check(saved.edit().putString("wifi.$identity",value).commit()) { "Could not save Wi-Fi choice" } }
 @Synchronized fun current(): NetworkTrust? {
  val manager=context.getSystemService(ConnectivityManager::class.java)
  val network=manager.allNetworks.firstOrNull { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true } ?: return null
  val capabilities=manager.getNetworkCapabilities(network) ?: return null
  val key=network.networkHandle
  val identity=identity(capabilities)
  choices[key]?.takeIf { it.identity==null || it.identity==identity }?.let { choice ->
   if (identity!=null && choice.identity==null) { remember(identity,choice.privacy);choice.identity=identity }
   return NetworkTrust(key,choice.privacy,if(identity!=null) "Saved for this Wi-Fi. All Unity connections remain encrypted with verified TLS 1.3." else "This connection is reviewed. Allow Wi-Fi name access to remember it after reconnecting; encryption stays on.",identity!=null,identity)
  }
  if(identity!=null) saved.getString("wifi.$identity",null)?.takeIf {it in listOf("public","private")}?.let {
   return NetworkTrust(key,it,"Saved for this Wi-Fi. All Unity connections remain encrypted with verified TLS 1.3.",true,identity)
  }
  val open = if (Build.VERSION.SDK_INT>=31) (capabilities.transportInfo as? WifiInfo)?.currentSecurityType in
   listOf(WifiInfo.SECURITY_TYPE_OPEN,WifiInfo.SECURITY_TYPE_OWE) else false
  return if (open || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
   NetworkTrust(key,"public","Public/shared Wi-Fi is suspected. All Unity data stays encrypted; no plaintext discovery is broadcast.",identity=identity)
  else NetworkTrust(key,"unknown","Unity cannot establish who manages this Wi-Fi. Choose Public/shared if unsure; encryption is always on.",identity=identity)
 }
 @Synchronized fun choose(key: Long, value: String) {
  require(value in listOf("public","private"))
  val current=current();check(current?.key==key) { "The Wi-Fi network changed; review the current network" }
  if (choices.size>=16) choices.clear()
  current.identity?.let { remember(it,value) }
  choices[key]=Choice(value,current.identity); deferred.remove(key)
 }
 @Synchronized fun shouldAsk(): NetworkTrust? = current()?.takeIf { it.privacy=="unknown" && it.key !in deferred }
 @Synchronized fun defer(key: Long) { if (deferred.size>=16) deferred.clear(); deferred.add(key) }
}
