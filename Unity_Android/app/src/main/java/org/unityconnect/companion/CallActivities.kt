package org.unityconnect.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.text.InputType
import android.view.Gravity
import android.widget.*

class DialerActivity : AdwaitaActivity() {
 private lateinit var ui: AdwaitaUi
 private lateinit var number: EditText
 private lateinit var history: LinearLayout
 override fun onCreate(saved: Bundle?) {
  super.onCreate(saved)
  ui = AdwaitaUi(this)
  val body = contentPage("Phone")
  val dial = ui.section(body, "Dial a number")
  number = ui.field(dial, "Phone number", saved?.getString("number") ?: intent?.data?.schemeSpecificPart ?: "", R.id.phone_number, InputType.TYPE_CLASS_PHONE).apply {
   hint = "Enter phone number"; setSingleLine(); gravity = Gravity.CENTER; textSize = 24f
   setSelectAllOnFocus(false); setSelection(length())
  }
  dial.addView(ui.keypad { tone ->
   val start = number.selectionStart.coerceAtLeast(0); val end = number.selectionEnd.coerceAtLeast(start)
   number.text.replace(start, end, tone); number.setSelection(start + tone.length)
  }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(12), 0, ui.dp(12), ui.dp(8)) })
  ui.action(dial, "Delete digit") {
   val start = number.selectionStart.coerceAtLeast(0); val end = number.selectionEnd.coerceAtLeast(start)
   if (start != end) number.text.delete(start, end) else if (start > 0) number.text.delete(start - 1, start)
  }.apply { id = R.id.phone_delete; setOnLongClickListener { number.text.clear(); true } }
  ui.action(dial, "Call", suggested = true) {
   try {
    val value = phoneNumber(number.text.toString())
    if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 42)
    else getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", value, null), Bundle())
   } catch (e: Exception) { Toast.makeText(this@DialerActivity, e.message, Toast.LENGTH_LONG).show() }
  }.id = R.id.phone_call
  val contacts = ui.section(body, "Contacts and access")
  ui.addRow(contacts, ui.row("Choose contact", "Select a saved phone number.", R.drawable.ic_phone, action = { startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), 41) }).view)
  ui.addRow(contacts, ui.row("Phone and history permissions", "Allow calls, contacts and recent call history.", R.drawable.ic_shield, action = { requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE), 42) }).view)
  history = ui.column(); body.addView(history); refreshHistory()
 }
 private fun refreshHistory() {
  history.removeAllViews()
  val card = ui.section(history, "Recent calls")
  if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
   ui.addRow(card, ui.row("Call history is private", "Allow history access above to show your recent calls.", R.drawable.ic_shield).view); return
  }
  var count = 0
  runCatching {
   contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE), null, null, "date DESC")?.use { cursor ->
    while (cursor.moveToNext() && count++ < 50) {
     val value = cursor.getString(0) ?: ""
     val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: value.ifBlank { "Unknown caller" }
     val date = android.text.format.DateUtils.getRelativeTimeSpanString(cursor.getLong(2))
     ui.addRow(card, ui.row(name, "${if (name != value && value.isNotBlank()) "$value · " else ""}$date", R.drawable.ic_phone, action = { number.setText(value); number.setSelection(number.length()) }).view)
    }
   }
  }.onFailure { ui.addRow(card, ui.row("History unavailable", "Try reviewing phone and history access.", R.drawable.ic_info).view) }
  if (count == 0 && card.childCount == 0) ui.addRow(card, ui.row("No recent calls", "Your call history will appear here.", R.drawable.ic_phone).view)
 }
 override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); number.setText(intent.data?.schemeSpecificPart ?: ""); number.setSelection(number.length()) }
 override fun onSaveInstanceState(out: Bundle) { out.putString("number", number.text.toString()); super.onSaveInstanceState(out) }
 override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) { super.onRequestPermissionsResult(code, permissions, results); refreshHistory() }
 override fun onActivityResult(code: Int, result: Int, data: Intent?) {
  super.onActivityResult(code, result, data)
  if (code == 41 && result == RESULT_OK) data?.data?.let { uri -> contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { if (it.moveToFirst()) { number.setText(it.getString(0)); number.setSelection(number.length()) } } }
 }
}
