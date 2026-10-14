package org.unityconnect.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import org.json.JSONArray
import org.json.JSONObject

internal fun phoneNumber(raw: String): String {
 require(raw.length in 1..64 && raw.all { it in '0'..'9' || it in "+ -()" }) { "Invalid phone number" }
 val number = raw.filter { it in '0'..'9' || it == '+' }
 require(number.any { it in '0'..'9' } && number.count { it == '+' } <= 1 && (!number.contains('+') || number.startsWith('+'))) { "Invalid phone number" }
 return number
}
internal fun callState(state: Int) = when (state) {
 Call.STATE_RINGING -> "ringing"
 Call.STATE_DIALING -> "dialing"
 Call.STATE_CONNECTING -> "connecting"
 Call.STATE_SELECT_PHONE_ACCOUNT -> "select_account"
 Call.STATE_ACTIVE -> "active"
 Call.STATE_HOLDING -> "held"
 Call.STATE_DISCONNECTING -> "disconnecting"
 Call.STATE_DISCONNECTED -> "disconnected"
 else -> "new"
}
internal fun requireCallAction(action: String, state: Int, capabilities: Int) {
 when (action) {
  "answer", "reject" -> check(state == Call.STATE_RINGING) { "The call is no longer ringing" }
  "hold" -> check(state == Call.STATE_ACTIVE && capabilities and Call.Details.CAPABILITY_HOLD != 0) { "This call cannot be held" }
  "unhold" -> check(state == Call.STATE_HOLDING && capabilities and Call.Details.CAPABILITY_HOLD != 0) { "This call cannot be resumed" }
  "dtmf" -> check(state == Call.STATE_ACTIVE) { "Keypad is available during an active call" }
  "hangup" -> check(state !in listOf(Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING)) { "The call has ended" }
  else -> error("Unsupported call action")
 }
}

class CallControl(service: CompanionService) : Module(service, "call_control") {
 override fun capability() = when {
  !enabled() -> status()
  !service.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) -> cap(id, "unsupported", "This device has no telephone service")
  !granted(Manifest.permission.CALL_PHONE) || !granted(Manifest.permission.READ_PHONE_STATE) -> cap(id, "permission_required", "Allow phone and call-state permissions")
  else -> status()
 }
 fun publish() {
  if (Looper.myLooper() != Looper.getMainLooper()) { Handler(Looper.getMainLooper()).post { publish() }; return }
  if (capability().getString("status") == "available" && service.connection.ready) {
   val snapshot = if (UnityInCallService.enabled(service)) UnityInCallService.instance?.snapshot() ?: UnityInCallService.emptySnapshot()
    else JSONObject().put("backend", "bluetooth_hfp").put("calls", JSONArray()).put("muted", false).put("route", "phone").put("routes", JSONArray())
   runCatching { service.event(id, snapshot) }
  }
 }
 override fun command(action: String, data: JSONObject): JSONObject {
  check(capability().getString("status") == "available") { capability().getString("reason") }
  return when (action) {
   "refresh" -> onMain { publish(); JSONObject().put("state", "refreshed") }
   "contacts" -> contacts(data)
   "history" -> history(data)
   "dial" -> onMain {
    val number = phoneNumber(data.getString("number"))
    // Telecom owns account selection and emergency-call handoff to the system dialer.
    val telecom = service.getSystemService(TelecomManager::class.java)
    check(!service.getSystemService(android.telephony.TelephonyManager::class.java).isEmergencyNumber(number)) { "Use the phone directly for emergency calls" }
    check(service.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) { "Allow phone permission" }
    telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
    JSONObject().put("state", "submitted").put("reason", "Waiting for phone call state")
   }
   "answer", "reject", "hangup", "hold", "unhold", "dtmf", "mute", "route" -> onMain {
    check(UnityInCallService.enabled(service)) { "Choose Unity as the Phone app to control phone speakerphone" }
    val calls = UnityInCallService.instance ?: error("No phone call is active")
    calls.control(action, data, remote = true)
   }
   else -> error("Call controls use the computer’s paired Bluetooth hands-free connection")
  }
 }
 private fun page(data: JSONObject): Pair<Int,Int> {
  val offset = if (data.has("offset")) data.getInt("offset") else 0
  val limit = if (data.has("limit")) data.getInt("limit") else 50
  require(offset in 0..100000 && limit in 1..100) { "Invalid page" }
  return offset to limit
 }
 private fun contacts(data: JSONObject): JSONObject {
  check(granted(Manifest.permission.READ_CONTACTS)) { "Allow contacts permission on the phone" }
  val (offset, limit) = page(data)
  val query = data.optString("query", ""); require(query.toByteArray().size <= 256 && !query.contains('\u0000'))
  val uri = if (query.isBlank()) ContactsContract.CommonDataKinds.Phone.CONTENT_URI else Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
  val items = JSONArray(); var more = false
  service.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone._ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null,
   "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC, ${ContactsContract.CommonDataKinds.Phone._ID} ASC")?.use { c ->
   if (c.moveToPosition(offset)) do {
    if (items.length() >= limit) { more = true; break }
    items.put(JSONObject().put("id", c.getLong(0).toString()).put("name", Protocol.text(c.getString(1) ?: "", 256)).put("number", Protocol.text(c.getString(2) ?: "", 64)))
   } while (c.moveToNext())
  }
  return JSONObject().put("contacts", items).put("next_offset", if (more) offset + items.length() else JSONObject.NULL)
 }
 private fun history(data: JSONObject): JSONObject {
  check(granted(Manifest.permission.READ_CALL_LOG)) { "Allow call history permission on the phone" }
  val (offset, limit) = page(data); val items = JSONArray(); var more = false
  val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
  service.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
   "${CallLog.Calls.DATE} >= ?", arrayOf(cutoff.toString()), "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC")?.use { c ->
   if (c.moveToPosition(offset)) do {
    if (items.length() >= limit) { more = true; break }
    items.put(JSONObject().put("id", c.getLong(0).toString()).put("number", Protocol.text(c.getString(1) ?: "", 64)).put("name", Protocol.text(c.getString(2) ?: "", 256))
     .put("timestamp", c.getLong(3)).put("duration", c.getLong(4)).put("type", c.getInt(5)))
   } while (c.moveToNext())
  }
  return JSONObject().put("calls", items).put("next_offset", if (more) offset + items.length() else JSONObject.NULL)
 }
}
