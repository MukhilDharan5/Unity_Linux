package org.unityconnect.companion

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.json.JSONObject

class UnityDeviceAdmin : DeviceAdminReceiver() {
 override fun onEnabled(context: Context, intent: Intent) { CompanionService.instance?.features?.publish() }
 override fun onDisabled(context: Context, intent: Intent) { CompanionService.instance?.features?.publish() }
}

class DeviceLock(service: CompanionService) : Module(service, "lock") {
 override fun capability() = when {
  !enabled() -> status()
  !active(service) -> cap(id, "user_action_required", "Activate phone locking in Android settings to let your computer lock this phone")
  else -> status()
 }
 override fun command(action: String, data: JSONObject): JSONObject {
  require(action == "lock" && data.length() == 0)
  check(enabled()) { "Enable remote locking on your phone" }
  check(active(service)) { service.prompt("Activate phone locking", "lock"); "Activate phone locking in Unity’s Features page" }
  service.getSystemService(DevicePolicyManager::class.java).lockNow()
  return JSONObject().put("state", "locked")
 }
 companion object {
  fun component(context: Context) = ComponentName(context, UnityDeviceAdmin::class.java)
  fun active(context: Context) = context.getSystemService(DevicePolicyManager::class.java).isAdminActive(component(context))
  fun activation(context: Context) = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
   .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
   .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Allow your paired computer to lock this phone. Unity requests only the lock-screen policy.")
 }
}
