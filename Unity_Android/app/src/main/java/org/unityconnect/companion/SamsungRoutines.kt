package org.unityconnect.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale

class SamsungRoutines(service: CompanionService) : Module(service, "samsung_routines") {
 companion object {
  const val CHANNEL = "unity.routines"
  private const val TAG = "unity.routines"
  private val sequence = AtomicInteger(1000)
  val keywords: Map<String, String> = linkedMapOf(
   "hotspot_on" to "UNITY_HOTSPOT_ON",
   "hotspot_off" to "UNITY_HOTSPOT_OFF",
   "disconnect_bluetooth" to "UNITY_BLUETOOTH_DISCONNECT"
  )
  fun deviceKeyword(address: String): String {
   require(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}").matches(address)) { "Invalid Bluetooth device address" }
   return "UNITY_BLUETOOTH_DISCONNECT_${address.replace(":", "").uppercase(Locale.ROOT)}"
  }
  fun supported() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
  fun channel(context: Context) {
   context.getSystemService(NotificationManager::class.java).createNotificationChannel(
    NotificationChannel(CHANNEL, "Samsung Routines triggers", NotificationManager.IMPORTANCE_DEFAULT).apply {
     description = "Notification keywords used by your configured Samsung Modes and Routines"
     setSound(null, null); enableVibration(false)
    })
  }
  fun notificationProblem(context: Context): String? {
   if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
    return "Allow Unity notifications for Samsung Routines triggers"
   val manager = context.getSystemService(NotificationManager::class.java)
   if (!manager.areNotificationsEnabled()) return "Allow Unity notifications for Samsung Routines triggers"
   channel(context)
   if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE)
    return "Enable the Samsung Routines triggers notification category"
   return null
  }
  fun setup(context: Context) = "Create routines in Samsung Modes and Routines. For each If condition, choose Notification received, select ${context.getString(R.string.app_name)}, and match one exact keyword:\n\n" +
   "${keywords.getValue("hotspot_on")}\nThen: Mobile Hotspot → On\n\n" +
   "${keywords.getValue("hotspot_off")}\nThen: Mobile Hotspot → Off\n\n" +
   "${keywords.getValue("disconnect_bluetooth")}\nThen: Disconnect from a Bluetooth device → choose the specific device.\n\n" +
   "For a device-specific disconnect, enable Phone connectivity and open Connected Bluetooth devices in Unity. Create a routine with that device’s exact keyword and select the same device in Samsung’s disconnect action.\n\n" +
   "Disable any automatic reversal when each routine ends. Allow Unity notifications and the Samsung Routines triggers category, then enable Samsung Routines in Unity. Your computer sends the keyword; Samsung runs the configured action. Unity cannot confirm execution."
 }
 override fun capability(): JSONObject {
  if (!supported()) return cap(id, "unsupported", "Requires Samsung Modes and Routines")
  if (!enabled()) return status()
  val problem = notificationProblem(service)
  return status(problem == null, problem ?: "Configure keyword routines on your phone; execution is not confirmed")
 }
 override fun command(action: String, data: JSONObject): JSONObject = onMain {
  val keyword = if (action == "disconnect_bluetooth_device") {
   require(data.length() == 1 && data.opt("address") is String) { "Choose exactly one Bluetooth device address" }
   val address = data.getString("address").uppercase(Locale.ROOT)
   val token = deviceKeyword(address)
   service.features.phoneConnectivity.requireConnected(address)
   token
  } else {
   require(data.length() == 0) { "This Samsung Routines action does not accept a payload" }
   keywords[action] ?: error("Unsupported Samsung Routines action")
  }
  val capability = capability()
  check(capability.getString("status") == "available") { capability.getString("reason") }
  val open = PendingIntent.getActivity(service, TAG.hashCode(), Intent(service, MainActivity::class.java)
   .putExtra("phone_action", "samsung_routines"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val notification = Notification.Builder(service, CHANNEL)
   .setSmallIcon(R.drawable.ic_unity_symbolic).setContentTitle(keyword)
   .setContentText("$keyword · Trigger sent; execution is not confirmed.")
   .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(60_000)
   .setVisibility(Notification.VISIBILITY_PRIVATE).build()
  val manager = service.getSystemService(NotificationManager::class.java)
  // Each command creates a fresh post, including repeated identical actions.
  // Keep one short-lived trigger without replacing the connection notification.
  manager.activeNotifications.filter { it.tag == TAG }.forEach { manager.cancel(TAG, it.id) }
  manager.notify(TAG, sequence.incrementAndGet(), notification)
  JSONObject().put("state", "trigger_sent").put("keyword", keyword).put("confirmed", false)
   .put("reason", "Samsung Routines trigger sent; execution is not confirmed")
 }
}
