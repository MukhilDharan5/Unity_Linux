package org.unityconnect.companion

import android.Manifest
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.Activity
import android.app.PendingIntent
import android.os.Build
import android.telephony.SmsMessage
import java.util.UUID
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

class SmsFeature(service: CompanionService) : Module(service, "sms") {
 private var observing = false
 @Volatile private var inboxEpoch = 0
 private var inboxHighId: Long? = null
 private val inboxEvents = java.util.concurrent.Executors.newSingleThreadExecutor()
 private var callbacksRegistered = false
 private val callbackAction = "${service.packageName}.SMS_${UUID.randomUUID()}"
 private val sends = linkedMapOf<String, SmsProgress>()
 private val callback = object : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
   val messageId = intent.getStringExtra("message_id") ?: return
   val part = intent.getIntExtra("part", -1)
   val delivery = intent.getBooleanExtra("delivery", false)
   val modemStatus = if (delivery) intent.getByteArrayExtra("pdu")?.let { runCatching { SmsMessage.createFromPdu(it, intent.getStringExtra("format") ?: "3gpp").status }.getOrNull() } else null
   val state = synchronized(sends) { runCatching { sends[messageId]?.update(part, delivery, resultCode == Activity.RESULT_OK, modemStatus) }.getOrNull() } ?: return
   if (capability().getString("status") == "available" && service.connection.ready) runCatching { service.event(id, JSONObject().put("message_id", messageId).put("state", state).put("revision", SystemClock.elapsedRealtime())) }
  }
 }
 @Suppress("UnspecifiedRegisterReceiverFlag") // Pre-33 has no flag API; action is random and PendingIntent targets this package.
 private fun registerCallbacks() {
  if (callbacksRegistered) return
  if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(callback, IntentFilter(callbackAction), Context.RECEIVER_NOT_EXPORTED)
  else service.registerReceiver(callback, IntentFilter(callbackAction))
  callbacksRegistered = true
 }
 fun close() { stop(); inboxEvents.shutdownNow(); if (callbacksRegistered) service.unregisterReceiver(callback); callbacksRegistered = false; synchronized(sends) { sends.clear() } }
 private val handler = Handler(Looper.getMainLooper())
 private val observer = object : ContentObserver(handler) {
  override fun onChange(selfChange: Boolean) {
   if (service.connection.ready && capability().getString("status") == "available") {
    runCatching { service.event(id, JSONObject().put("revision", SystemClock.elapsedRealtime())) }
    val epoch = inboxEpoch
    runCatching { inboxEvents.execute { runCatching { publishIncoming(epoch) } } }
   }
  }
 }
 fun watch() { handler.post {
  if (observing && capability().getString("status") != "available") { service.contentResolver.unregisterContentObserver(observer); observing = false; inboxEpoch++ }
  if (!observing && capability().getString("status") == "available") {
   val epoch = ++inboxEpoch
   runCatching { inboxEvents.execute {
    // Establish a fresh baseline on connection/permission changes; history
    // and messages received offline must not become a reconnect alert storm.
    runCatching { if (epoch == inboxEpoch) inboxHighId = newestInboxId() }
   } }
   service.contentResolver.registerContentObserver(Uri.parse("content://sms"), true, observer); observing = true
  }
 } }
 fun stop() { handler.post { if (observing) service.contentResolver.unregisterContentObserver(observer); observing = false; inboxEpoch++ } }
 private fun newestInboxId(): Long = service.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id"), null, null, "_id DESC")?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
 private fun publishIncoming(epoch: Int) {
  if (epoch != inboxEpoch || !service.connection.ready || capability().getString("status") != "available") return
  val after = inboxHighId ?: return
  service.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id", "address", "body", "date"), "_id > ?", arrayOf(after.toString()), "_id ASC")?.use { c ->
   while (c.moveToNext()) {
    if (epoch != inboxEpoch || !service.connection.ready || capability().getString("status") != "available") return
    val row = c.getLong(0)
    val incoming = JSONObject().put("id", row.toString()).put("address", Protocol.text(c.getString(1).orEmpty(), 256))
     .put("body", Protocol.text(c.getString(2).orEmpty(), 4096)).put("timestamp", c.getLong(3).coerceAtLeast(0))
    service.event(id, JSONObject().put("revision", SystemClock.elapsedRealtime()).put("incoming", incoming), true)
    inboxHighId = row
   }
  }
 }
 fun notificationMessageId(app: String, timestamp: Long, body: String): String? {
  if (capability().getString("status") != "available" || timestamp <= 0 || body.isEmpty() || app != Telephony.Sms.getDefaultSmsPackage(service)) return null
  // Correlate by exact provider timestamp/body and accept only a unique row.
  // This also avoids confusing RCS/app messages with a real inbox SMS.
  return service.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id"), "date = ? AND body = ?", arrayOf(timestamp.toString(), body), null)?.use { c ->
   if (!c.moveToFirst()) null else c.getLong(0).toString().takeIf { !c.moveToNext() }
  }
 }
 override fun capability() = status(granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.SEND_SMS), "Grant SMS permissions; store distribution policies may require default-handler role or an exception")
 override fun command(action: String, data: JSONObject): JSONObject {
  check(capability().getString("status") == "available") { "Enable SMS and grant permissions" }
  return when (action) {
   "list" -> {
    val items = JSONArray()
    val address = if (data.has("address")) data.getString("address") else null
    require(address == null || address.toByteArray().size <= 256)
    val offset = if (data.has("offset")) data.getInt("offset") else 0
    val limit = if (data.has("limit")) data.getInt("limit") else 80
    require(offset in 0..100000 && limit in 1..100)
    var more = false
    var budget = 0
    service.contentResolver.query(Uri.parse("content://sms"), arrayOf("_id", "address", "body", "date", "type", "thread_id"),
     if (address != null) "address = ?" else null, address?.let { arrayOf(it) }, "date DESC, _id DESC")?.use { c ->
     if (c.moveToPosition(offset)) do {
      if (items.length() >= limit) { more = true; break }
      val message = JSONObject().put("id", c.getLong(0).toString()).put("address", Protocol.text(c.getString(1) ?: "", 256))
       .put("body", Protocol.text(c.getString(2) ?: "", 4096)).put("timestamp", c.getLong(3))
       .put("direction", if (c.getInt(4) == 1) "incoming" else "outgoing").put("type", c.getInt(4)).put("thread_id", c.getLong(5).toString())
      budget += message.toString().toByteArray().size + 1
      if (budget > 58000) { more = true; break }
      items.put(message)
     } while (c.moveToNext())
    }
    JSONObject().put("messages", items).put("next_offset", if (more) offset + items.length() else JSONObject.NULL)
   }
   "send" -> {
    val address = phoneNumber(data.getString("address")); val body = data.getString("body")
    require(body.isNotEmpty() && body.toByteArray().size <= 4096)
    val manager = service.getSystemService(SmsManager::class.java)
    check(manager.subscriptionId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) { "Choose a default SMS SIM in phone settings" }
    val parts = manager.divideMessage(body)
    require(parts.size in 1..32) { "Message has too many SMS parts" }
    registerCallbacks()
    val messageId = UUID.randomUUID().toString().replace("-", "")
    synchronized(sends) { while (sends.size >= 100) sends.remove(sends.keys.first()); sends[messageId] = SmsProgress(parts.size) }
    fun intents(delivery: Boolean) = ArrayList(parts.indices.map { part ->
     PendingIntent.getBroadcast(service, 0, Intent(callbackAction).setPackage(service.packageName).setData(Uri.parse("unity-sms:$messageId/$part/$delivery"))
      .putExtra("message_id", messageId).putExtra("part", part).putExtra("delivery", delivery), PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
    })
    try { manager.sendMultipartTextMessage(address, null, parts, intents(false), intents(true)) }
    catch (e: Exception) { synchronized(sends) { sends.remove(messageId) }; throw e }
    JSONObject().put("state", "submitted_to_modem").put("message_id", messageId).put("reason", "Waiting for carrier sent and delivery reports")
   }
   else -> error("Unsupported action")
  }
 }
}
class CallNotifications(service: CompanionService) : Module(service, "call_notifications") {
 override fun capability() = status(granted(Manifest.permission.READ_PHONE_STATE), "Grant phone state permission. Caller number may be redacted by Android.")
 fun publish(intent: Intent) {
  if (capability().getString("status") != "available") return
  val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
  val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
  runCatching { service.event(id, JSONObject().put("state", state.lowercase()).put("number", number.take(64))) }
 }
 override fun command(action: String, data: JSONObject): JSONObject {
  require(action == "refresh")
  val value = service.getSystemService(TelephonyManager::class.java).callState
  publish(Intent().putExtra(TelephonyManager.EXTRA_STATE, when (value) { TelephonyManager.CALL_STATE_RINGING -> TelephonyManager.EXTRA_STATE_RINGING; TelephonyManager.CALL_STATE_OFFHOOK -> TelephonyManager.EXTRA_STATE_OFFHOOK; else -> TelephonyManager.EXTRA_STATE_IDLE }))
  return JSONObject().put("state", "refreshed")
 }
}

/** Multipart reports are idempotent and delivery never means only one part succeeded. */
internal class SmsProgress(private val parts: Int) {
 private val sent = mutableSetOf<Int>()
 private val delivered = mutableSetOf<Int>()
 private var failed = false
 private var deliveryFailed = false
 fun update(part: Int, delivery: Boolean, ok: Boolean, modemStatus: Int?): String {
  require(part in 0 until parts)
  if (!delivery) { if (ok) sent.add(part) else failed = true }
  else if (!ok || (modemStatus != null && modemStatus >= 64)) deliveryFailed = true
  else if (modemStatus != null && modemStatus in 0..31) delivered.add(part)
  return when { failed -> "failed"; deliveryFailed -> "delivery_failed"; delivered.size == parts -> "delivered"; sent.size == parts -> "sent"; else -> "submitted_to_modem" }
 }
}
