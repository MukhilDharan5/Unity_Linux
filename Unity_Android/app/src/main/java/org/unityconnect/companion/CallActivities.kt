package org.unityconnect.companion

import android.Manifest
import android.app.Activity
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

class DialerActivity : Activity() {
 private lateinit var number: EditText
 private lateinit var history: LinearLayout
 override fun onCreate(saved: Bundle?) {
  super.onCreate(saved)
  val ui = AdwaitaUi(this); val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(24)); setBackgroundColor(ui.window) }
  setContentView(ScrollView(this).apply { addView(body) })
  body.addView(ui.label("Phone", 26f, true))
  number = EditText(this).apply { hint = "Phone number"; inputType = InputType.TYPE_CLASS_PHONE; setText(saved?.getString("number") ?: intent?.data?.schemeSpecificPart ?: "") }; body.addView(number)
  keypad(this, body) { number.append(it) }
  body.addView(Button(this).apply { text = "Call"; setOnClickListener {
   try {
    val value = phoneNumber(number.text.toString())
    if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 42)
    else getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", value, null), Bundle())
   } catch (e: Exception) { Toast.makeText(this@DialerActivity, e.message, Toast.LENGTH_LONG).show() }
  } })
  body.addView(Button(this).apply { text = "Choose contact"; setOnClickListener { startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), 41) } })
  body.addView(Button(this).apply { text = "Phone and history permissions"; setOnClickListener { requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE), 42) } })
  history = ui.column(); body.addView(history); refreshHistory()
 }
 private fun refreshHistory() {
  history.removeAllViews(); history.addView(TextView(this).apply { text = "Recent calls"; textSize = 20f })
  if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return
  runCatching {
   contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE), null, null, "date DESC")?.use { cursor ->
    var count = 0
    while (cursor.moveToNext() && count++ < 50) {
     val value = cursor.getString(0) ?: ""
     history.addView(Button(this).apply { text = "${cursor.getString(1)?.takeIf { it.isNotBlank() } ?: value} · ${android.text.format.DateUtils.getRelativeTimeSpanString(cursor.getLong(2))}"; setOnClickListener { number.setText(value) } })
    }
   }
  }
 }
 override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); number.setText(intent.data?.schemeSpecificPart ?: "") }
 override fun onSaveInstanceState(out: Bundle) { out.putString("number", number.text.toString()); super.onSaveInstanceState(out) }
 override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) { super.onRequestPermissionsResult(code, permissions, results); refreshHistory() }
 override fun onActivityResult(code: Int, result: Int, data: Intent?) {
  super.onActivityResult(code, result, data)
  if (code == 41 && result == RESULT_OK) data?.data?.let { uri -> contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { if (it.moveToFirst()) number.setText(it.getString(0)) } }
 }
}

private fun keypad(activity: Activity, body: LinearLayout, pressed: (String) -> Unit) {
 for (row in listOf("123", "456", "789", "*0#")) {
  val line = LinearLayout(activity).apply { gravity = Gravity.CENTER }
  row.forEach { tone -> line.addView(Button(activity).apply { text = tone.toString(); contentDescription = "Key $tone"; setOnClickListener { pressed(tone.toString()) } }, LinearLayout.LayoutParams(0, -2, 1f)) }
  body.addView(line)
 }
}
