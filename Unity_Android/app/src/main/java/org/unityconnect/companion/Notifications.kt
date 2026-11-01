package org.unityconnect.companion

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.content.Context
import android.content.ComponentName
import android.app.NotificationManager
import android.os.Bundle
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

// Android notification tags are arbitrary Unicode. Never truncate the source
// key: that can exceed the wire byte limit or alias two different notifications.
internal fun notificationId(key: String): String =
 "n:" + Protocol.hex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)))

class PhoneNotifications : NotificationListenerService() {
 companion object {
  @Volatile var instance: PhoneNotifications? = null
  fun ensureConnected(context: Context) {
   if (instance != null || !context.getSharedPreferences("settings", 0).getBoolean("enable.notifications", false)) return
   val component = ComponentName(context, PhoneNotifications::class.java)
   if (context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)) {
    runCatching { requestRebind(component) }
   }
  }
 }
 private val events = java.util.concurrent.Executors.newSingleThreadExecutor()
 override fun onListenerConnected() { instance = this; CompanionService.instance?.features?.publish() }
 override fun onListenerDisconnected() { instance = null; CompanionService.instance?.features?.publish(); ensureConnected(this) }
 override fun onDestroy() { instance = null; events.shutdownNow(); super.onDestroy() }
 private fun enabled() = getSharedPreferences("settings", 0).getBoolean("enable.notifications", false)
 override fun onNotificationPosted(sbn: StatusBarNotification) { publish(sbn, true) }
 override fun onNotificationRemoved(sbn: StatusBarNotification) {
  if (!enabled()) return
  events.execute { runCatching { CompanionService.instance?.event("notifications", JSONObject().put("id", notificationId(sbn.key)).put("removed", true), true) } }
 }
 fun publishAll() { if (enabled()) runCatching { activeNotifications?.take(200)?.forEach { publish(it, false) } } }
 private fun publish(sbn: StatusBarNotification, alert: Boolean) {
  if (!enabled() || sbn.packageName == packageName) return
  val excluded = getSharedPreferences("settings", 0).getString("notification_excluded", "")!!.split(',').map { it.trim() }
  if (sbn.packageName in excluded) return
  val n = sbn.notification
  val actions = JSONArray()
  n.actions?.take(16)?.forEachIndexed { index, action -> actions.put(JSONObject().put("id", index.toString()).put("title", action.title.toString().take(128)).put("reply", !action.remoteInputs.isNullOrEmpty())) }
  val value = JSONObject().put("id", notificationId(sbn.key)).put("app", sbn.packageName.take(256))
   .put("app_name", Protocol.text(runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName), 256))
   .put("timestamp", sbn.postTime)
   .put("title", Protocol.text((n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "").replace("\u0000", ""), 512))
   .put("text", Protocol.text(((n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: "").replace("\u0000", ""), 8192))
   .put("actions", actions).put("removed", false)
   .put("alert", alert && !sbn.isOngoing && n.flags and Notification.FLAG_GROUP_SUMMARY == 0)
  events.execute { runCatching {
   val service = CompanionService.instance ?: return@runCatching
   val message = if (Build.VERSION.SDK_INT >= 30) Notification.MessagingStyle.Message.getMessagesFromBundleArray(n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)).lastOrNull() else null
   val body = message?.text?.toString() ?: (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
   service.features.sms.notificationMessageId(sbn.packageName, message?.timestamp ?: n.`when`, body)?.let { value.put("sms_id", it) }
   if (enabled() && service.connection.ready) service.event("notifications", value, true)
  } }
 }
 fun action(key: String, index: Int, reply: String?) {
  val n = activeNotifications?.firstOrNull { notificationId(it.key) == key } ?: error("Notification no longer exists")
  val action = n.notification.actions?.getOrNull(index) ?: error("Action no longer exists")
  val intent = Intent()
  if (reply != null) {
   require(reply.toByteArray().size <= 4096)
   val inputs = action.remoteInputs ?: error("Action does not support reply")
   val results = Bundle(); inputs.forEach { results.putCharSequence(it.resultKey, reply) }
   RemoteInput.addResultsToIntent(inputs, intent, results)
  }
  action.actionIntent.send(this, 0, intent)
 }
 fun dismiss(key: String) {
  val notification = activeNotifications?.firstOrNull { notificationId(it.key) == key } ?: error("Notification no longer exists")
  cancelNotification(notification.key)
 }
}
class NotificationFeature(service: CompanionService) : Module(service, "notifications") {
 override fun capability() = status(PhoneNotifications.instance != null, "Enable notification listener access on phone")
 override fun command(action: String, data: JSONObject): JSONObject {
  val listener = PhoneNotifications.instance ?: error("Notification access unavailable")
  when (action) { "dismiss" -> listener.dismiss(data.getString("id")); "list" -> listener.publishAll(); else -> error("Unsupported action") }
  return JSONObject().put("state", "submitted")
 }
}
class NotificationActions(service: CompanionService) : Module(service, "notification_actions") {
 override fun capability() = status(PhoneNotifications.instance != null && service.getSharedPreferences("settings", 0).getBoolean("enable.notifications", false), "Enable notification listener and mirroring")
 override fun command(action: String, data: JSONObject): JSONObject {
  require(action in listOf("invoke", "reply")); val index = data.getString("action").toInt(); require(index in 0..15)
  PhoneNotifications.instance?.action(data.getString("id"), index, if (action == "reply") data.getString("text") else null) ?: error("Notification listener unavailable")
  return JSONObject().put("state", "submitted")
 }
}
