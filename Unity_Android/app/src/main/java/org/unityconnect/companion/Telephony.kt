package org.unityconnect.companion

import android.Manifest
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject

class SmsFeature(service: CompanionService) : Module(service, "sms") {
 private var observing = false
 private val handler = Handler(Looper.getMainLooper())
 private val observer = object : ContentObserver(handler) {
  override fun onChange(selfChange: Boolean) {
   if (service.connection.ready && capability().getString("status") == "available") {
    runCatching { service.event(id, JSONObject().put("revision", SystemClock.elapsedRealtime())) }
   }
  }
 }
 fun watch() { handler.post {
  if (!observing && capability().getString("status") == "available") {
   service.contentResolver.registerContentObserver(Uri.parse("content://sms"), true, observer); observing = true
  }
 } }
 fun stop() { handler.post { if (observing) service.contentResolver.unregisterContentObserver(observer); observing = false } }
 override fun capability() = status(granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.SEND_SMS), "Grant SMS permissions; store distribution policies may require default-handler role or an exception")
 override fun command(action: String, data: JSONObject): JSONObject {
  return when (action) {
   "list" -> {
    val items = JSONArray()
    val address = if (data.has("address")) data.getString("address") else null
    require(address == null || address.toByteArray().size <= 256)
    var budget = 0
    service.contentResolver.query(Uri.parse("content://sms"), arrayOf("_id", "address", "body", "date", "type", "thread_id"),
     if (address != null) "address = ?" else null, address?.let { arrayOf(it) }, "date DESC")?.use { c ->
     while (c.moveToNext() && items.length() < 80) {
      val message = JSONObject().put("id", c.getLong(0).toString()).put("address", Protocol.text(c.getString(1) ?: "", 256))
       .put("body", Protocol.text(c.getString(2) ?: "", 4096)).put("timestamp", c.getLong(3))
       .put("direction", if (c.getInt(4) == 1) "incoming" else "outgoing").put("type", c.getInt(4)).put("thread_id", c.getLong(5).toString())
      budget += message.toString().toByteArray().size + 1
      if (budget > 58000) break
      items.put(message)
     }
    }
    JSONObject().put("messages", items)
   }
   "send" -> {
    val address = data.getString("address"); val body = data.getString("body")
    require(address.length in 1..64 && address.all { it.isDigit() || it in "+ -()" })
    require(body.isNotEmpty() && body.toByteArray().size <= 4096)
    val manager = service.getSystemService(SmsManager::class.java)
    val parts = manager.divideMessage(body)
    manager.sendMultipartTextMessage(address, null, parts, null, null)
    JSONObject().put("state", "submitted_to_modem").put("reason", "Carrier delivery has not been confirmed")
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
 override fun command(action: String, data: JSONObject): JSONObject { error("Call state is event-driven; no unrestricted call control") }
}
