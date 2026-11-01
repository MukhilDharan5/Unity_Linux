package org.unityconnect.companion

import android.Manifest
import android.bluetooth.*
import android.content.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Read phone radio state. Privileged changes are performed by locally configured Samsung routines. */
class PhoneConnectivity(service: CompanionService) : Module(service, "phone_connectivity") {
 companion object { const val HOTSPOT_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED" }
 private val main = Handler(Looper.getMainLooper())
 private val manager get() = service.getSystemService(BluetoothManager::class.java)
 private val adapter get() = manager?.adapter
 private val profiles = mutableMapOf<Int, BluetoothProfile>()
 private val requested = mutableSetOf<Int>()
 private val acl = linkedMapOf<String, BluetoothDevice>()
 private var watching = false
 private var generation = 0
 private var lastPublished = ""
 private var hotspotEnabled: Boolean? = null
 private fun hotspotChanged(intent: Intent?) {
  if (intent?.action == HOTSPOT_STATE_CHANGED) hotspotEnabled = when (intent.getIntExtra("wifi_state", -1)) {
   13 -> true; 11 -> false; else -> null
  }
 }
 private fun bluetoothPermission() = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
 private val receiver = object : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
   if (!watching || !enabled()) return
   hotspotChanged(intent)
   if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED || intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
    val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
     else @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
    if (device != null && bluetoothPermission()) {
     val address = device.address.uppercase(Locale.ROOT)
     if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) { if (acl.size < 64 || acl.containsKey(address)) acl[address] = device }
     else acl.remove(address)
    }
   }
   if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
       intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_OFF) closeProfiles()
   publish()
   // Profile lists can settle after the ACL broadcast.
   main.removeCallbacks(settled); main.postDelayed(settled, 500)
  }
 }
 private val settled = Runnable { if (watching) publish() }
 private val poll = object : Runnable {
  override fun run() {
   if (!watching) return
   if (!enabled() || !service.connection.ready) { stopNow(); return }
   publish(); main.postDelayed(this, 10_000)
  }
 }
 override fun capability() = status(reason = "Phone mobile hotspot state and connected Bluetooth devices; allow Nearby devices for Bluetooth")
 fun watch() { main.post {
  if (!enabled() || !service.connection.ready) { stopNow(); return@post }
  if (!watching) {
   watching = true; generation++
   val filter = IntentFilter(HOTSPOT_STATE_CHANGED).apply {
    addAction(BluetoothAdapter.ACTION_STATE_CHANGED); addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
    addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED); addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
    addAction(BluetoothHearingAid.ACTION_CONNECTION_STATE_CHANGED)
    if (Build.VERSION.SDK_INT >= 33) addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
   }
   hotspotChanged(if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else service.registerReceiver(receiver, filter))
   main.postDelayed(poll, 10_000)
  }
  publish()
 } }
 private fun openProfiles() {
  if (!watching || !bluetoothPermission() || runCatching { adapter?.isEnabled }.getOrNull() != true) { closeProfiles(); return }
  val epoch = generation
  val ids = mutableListOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.HEARING_AID, BluetoothProfile.HID_DEVICE)
  if (Build.VERSION.SDK_INT >= 33) ids.add(BluetoothProfile.LE_AUDIO)
  for (id in ids) if (requested.add(id)) {
   val listener = object : BluetoothProfile.ServiceListener {
    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { main.post {
     if (!watching || epoch != generation || !enabled() || !bluetoothPermission()) {
      runCatching { adapter?.closeProfileProxy(profile, proxy) }; return@post
     }
     profiles[profile] = proxy; publish()
    } }
    override fun onServiceDisconnected(profile: Int) { main.post {
     if (epoch == generation) { profiles.remove(profile); requested.remove(profile); publish() }
    } }
   }
   if (!runCatching { adapter?.getProfileProxy(service, listener, id) == true }.getOrDefault(false)) requested.remove(id)
  }
 }
 private fun closeProfiles() {
  generation++
  profiles.forEach { (id, proxy) -> runCatching { adapter?.closeProfileProxy(id, proxy) } }
  profiles.clear(); requested.clear(); acl.clear()
 }
 fun snapshot(): JSONObject = onMain {
  val value = JSONObject().put("hotspot_enabled", JSONObject.NULL).put("bluetooth_enabled", JSONObject.NULL)
   .put("bluetooth_status", "disabled").put("devices", JSONArray()).put("complete", false)
   .put("reason", "Enable Phone connectivity on your phone")
  if (!enabled()) return@onMain value
  // This is the phone's access-point broadcast, separate from station Wi-Fi.
  // Some OS builds hide it; an unavailable state stays unknown, never "off".
  hotspotChanged(runCatching { service.registerReceiver(null, IntentFilter(HOTSPOT_STATE_CHANGED)) }.getOrNull())
  value.put("hotspot_enabled", hotspotEnabled ?: JSONObject.NULL)
  if (!bluetoothPermission()) { closeProfiles(); return@onMain value.put("bluetooth_status", "permission_required").put("reason", "Allow Nearby devices in Unity on the phone") }
  val adapter = adapter ?: return@onMain value.put("bluetooth_status", "unsupported").put("reason", "This phone has no Bluetooth adapter")
  val on = runCatching { adapter.isEnabled }.getOrNull()
  value.put("bluetooth_enabled", on ?: JSONObject.NULL)
  if (on != true) { closeProfiles(); return@onMain value.put("bluetooth_status", if (on == false) "off" else "unavailable").put("reason", if (on == false) "Phone Bluetooth is off" else "Phone Bluetooth state is unavailable") }
  val devices = linkedMapOf<String, Pair<BluetoothDevice, MutableSet<String>>>()
  fun add(device: BluetoothDevice, source: String) {
   val address = device.address.uppercase(Locale.ROOT)
   if (!BluetoothAdapter.checkBluetoothAddress(address) || devices.size >= 64 && !devices.containsKey(address)) return
   devices.getOrPut(address) { device to linkedSetOf() }.second.add(source)
  }
  acl.values.forEach { add(it, "ACL") }
  service.connection.connectedBluetoothAddress?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }?.let {
   runCatching { add(adapter.getRemoteDevice(it), "Unity") }
  }
  var failed = false
  profiles.forEach { (id, proxy) ->
   runCatching { proxy.connectedDevices.forEach { add(it, when (id) {
    BluetoothProfile.A2DP -> "Audio"; BluetoothProfile.HEADSET -> "Calls"; BluetoothProfile.HEARING_AID -> "Hearing aid"
    BluetoothProfile.HID_DEVICE -> "HID"; else -> "LE Audio"
   }) } }.onFailure { failed = true }
  }
  for (id in listOf(BluetoothProfile.GATT, BluetoothProfile.GATT_SERVER)) {
   try { manager?.getConnectedDevices(id)?.forEach { add(it, "BLE") } }
   catch (_: SecurityException) {
    closeProfiles(); return@onMain value.put("bluetooth_enabled", JSONObject.NULL).put("bluetooth_status", "permission_required")
     .put("reason", "Allow Nearby devices in Unity on the phone")
   } catch (_: RuntimeException) { failed = true }
  }
  val array = JSONArray()
  devices.toSortedMap().forEach { (address, entry) ->
   val name = try { entry.first.name }
    catch (_: SecurityException) {
     closeProfiles(); return@onMain value.put("bluetooth_enabled", JSONObject.NULL).put("bluetooth_status", "permission_required")
      .put("reason", "Allow Nearby devices in Unity on the phone")
    } catch (_: RuntimeException) { null }
   array.put(JSONObject().put("address", address).put("name", Protocol.text(name?.replace("\u0000", "") ?: "Bluetooth device", 256))
    .put("profiles", JSONArray(entry.second.sorted())))
  }
  value.put("devices", array).put("bluetooth_status", if (failed) "partial" else "available")
   .put("reason", "Connected profiles and live Bluetooth links reported by Android; some existing device types may be unavailable")
 }
 fun requireConnected(address: String) {
  check(enabled()) { "Enable Phone connectivity on the phone" }
  check(bluetoothPermission()) { "Allow Nearby devices on the phone" }
  val state = snapshot()
  check(state.optBoolean("bluetooth_enabled") && (0 until state.getJSONArray("devices").length()).any {
   state.getJSONArray("devices").getJSONObject(it).getString("address") == address
  }) { "This Bluetooth device is no longer reported connected; refresh the device list" }
 }
 fun publish(force: Boolean = false) {
  if (Looper.myLooper() != Looper.getMainLooper()) { main.post { publish(force) }; return }
  if (!enabled() || !service.connection.ready) return
  openProfiles()
  val state = snapshot(); val text = state.toString()
  if (force || text != lastPublished) runCatching { service.event(id, state); lastPublished = text }
  service.uiChanged()
 }
 override fun command(action: String, data: JSONObject): JSONObject = onMain {
  require(action == "refresh" && data.length() == 0)
  check(enabled()) { "Enable Phone connectivity on the phone" }
  openProfiles(); val state = snapshot(); publish(true); state
 }
 fun stop() { main.post { stopNow() } }
 private fun stopNow() {
  if (watching) { watching = false; runCatching { service.unregisterReceiver(receiver) } }
  main.removeCallbacks(poll); main.removeCallbacks(settled); closeProfiles(); lastPublished = ""; hotspotEnabled = null
 }
}
