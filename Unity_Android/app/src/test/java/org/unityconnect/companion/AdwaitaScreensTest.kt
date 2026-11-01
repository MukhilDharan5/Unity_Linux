package org.unityconnect.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.hardware.biometrics.BiometricManager
import android.net.Uri
import android.os.Looper
import android.telecom.Call
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowBiometricManager
import org.robolectric.shadows.ShadowBiometricPrompt
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.security.Signature

/** Exercise the real secondary screens, including state changes while they remain visible. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdwaitaScreensTest {
 private fun configure(dark: Boolean = false, small: Boolean = false) {
  RuntimeEnvironment.setQualifiers(if (small) "w320dp-h740dp-notnight-xhdpi" else "w393dp-h852dp-notnight-xhdpi")
  RuntimeEnvironment.setFontScale(if (small) 1.6f else 1f)
  RuntimeEnvironment.getApplication().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("appearance", if (dark) "dark" else "light").commit()
 }
 private fun root(activity: Activity) = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
 private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
 private fun button(activity: Activity, text: String) = views(root(activity)).filterIsInstance<Button>().first { it.text.toString() == text }
 private fun capture(activity: Activity, name: String) = captureView(root(activity), activity, name)
 private fun captureView(view: View, activity: Activity, name: String, wrapHeight: Boolean = false) {
  val metrics = activity.resources.displayMetrics
  val width = (activity.resources.configuration.screenWidthDp * metrics.density).toInt()
  val height = (activity.resources.configuration.screenHeightDp * metrics.density).toInt()
  view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, if (wrapHeight) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY))
  view.layout(0, 0, width, view.measuredHeight)
  val bitmap = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
  view.draw(Canvas(bitmap))
  val out = File("build/reports/unity-preview/$name.png"); out.parentFile!!.mkdirs()
  out.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
 }
 private fun assertTextFits(activity: Activity) {
  views(root(activity)).filterIsInstance<TextView>().filter { it !is EditText && it.visibility == View.VISIBLE && it.text.isNotEmpty() }.forEach { text ->
   assertNotNull(text.text.toString(), text.layout)
   for (line in 0 until text.layout.lineCount) assertTrue("Text exceeds its container: ${text.text}", text.layout.getLineMax(line) <= text.width - text.paddingLeft - text.paddingRight + 2)
  }
 }
 @Test fun dialerEditsAtTheCursorDeletesAndRetainsItsDraftOnRecreation() {
  configure()
  val controller = Robolectric.buildActivity(DialerActivity::class.java, Intent(Intent.ACTION_DIAL, Uri.parse("tel:15550123"))).setup()
  try {
   val activity = controller.get(); val field = activity.findViewById<EditText>(R.id.phone_number)
   field.setSelection(field.length())
   views(root(activity)).first { it.contentDescription == "Key 4" }.performClick()
   assertEquals("155501234", field.text.toString())
   activity.findViewById<View>(R.id.phone_delete).performClick(); assertEquals("15550123", field.text.toString())
   field.setSelection(0, 4); views(root(activity)).first { it.contentDescription == "Key 2" }.performClick()
   assertEquals("20123", field.text.toString())
   capture(activity, "phone-dialer-light")
   controller.recreate(); assertEquals("20123", controller.get().findViewById<EditText>(R.id.phone_number).text.toString())
   controller.get().findViewById<View>(R.id.phone_delete).performLongClick()
   assertEquals("", controller.get().findViewById<EditText>(R.id.phone_number).text.toString())
  } finally { controller.pause().stop().destroy() }
 }
 @Test fun dialerHonorsSavedDarkAppearanceAndKeyboardSafeAreasAtLargeText() {
  configure(dark = true, small = true)
  val controller = Robolectric.buildActivity(DialerActivity::class.java).setup()
  try {
   val activity = controller.get(); val view = root(activity)
   assertEquals(Color.rgb(36, 36, 36), AdwaitaUi(activity).window)
   val insets = WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 30))
    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 280)).build()
   view.dispatchApplyWindowInsets(insets)
   assertEquals(24, view.paddingTop); assertEquals(280, view.paddingBottom)
   view.dispatchApplyWindowInsets(WindowInsets.Builder().build())
   capture(activity, "phone-dialer-large-text-dark"); assertTextFits(activity)
  } finally { controller.pause().stop().destroy() }
 }
 private class Credential : AuthenticationCredential {
  var resets = 0
  override fun present() = resets == 0
  override fun create() {}
  override fun signature(): Signature = error("This UI fixture never signs")
  override fun fingerprint(): String { check(present()); return "90".repeat(32) }
  override fun enrollmentBytes() = byteArrayOf()
  override fun enrollment(signed: ByteArray) = JSONObject()
  override fun reset() { resets++ }
 }
 private fun credential(service: CompanionService): Credential {
  service.getSharedPreferences("settings", 0).edit().putBoolean("enable.authentication", true).putString("fingerprint", "56".repeat(32)).commit()
  Shadow.extract<ShadowBiometricManager>(service.getSystemService(BiometricManager::class.java)).apply { setCanAuthenticate(true); setAuthenticatorType(BiometricManager.Authenticators.BIOMETRIC_STRONG) }
  return Credential().also { ReflectionHelpers.setField(service.features.authentication, "credential", it) }
 }
 @Test fun credentialResetRequiresConfirmationAndKeepsTheExistingKeyOnCancel() {
  configure()
  val service = Robolectric.buildService(CompanionService::class.java).create()
  val credential = credential(service.get())
  val controller = Robolectric.buildActivity(PhoneAuthenticationActivity::class.java).setup()
  try {
   val activity = controller.get()
   capture(activity, "fingerprint-setup-light")
   activity.findViewById<View>(R.id.authentication_reset).performClick()
   val dialog = ShadowAlertDialog.getLatestAlertDialog()
   assertTrue(dialog.isShowing)
   shadowOf(Looper.getMainLooper()).idle()
   assertNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE).transformationMethod)
   captureView(dialog.window!!.decorView, activity, "fingerprint-reset-dialog-light", wrapHeight = true)
   dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); shadowOf(Looper.getMainLooper()).idle(); assertEquals(0, credential.resets)
   activity.findViewById<View>(R.id.authentication_reset).performClick()
   ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
   shadowOf(Looper.getMainLooper()).idle()
   assertEquals(1, credential.resets)
   assertEquals("No fingerprint credential yet", activity.findViewById<TextView>(R.id.authentication_credential).text.toString())
   assertNull(ShadowBiometricPrompt.getCurrentPrompt())
  } finally { controller.pause().stop().destroy(); service.destroy() }
 }
 @Test fun reviewShowsTheBoundRequestAndDisablesApprovalWhenItEnds() {
  configure(dark = true)
  val service = Robolectric.buildService(CompanionService::class.java).create(); credential(service.get())
  val auth = service.get().features.authentication
  val request = "ab".repeat(16)
  val challenge = JSONObject(javaClass.getResourceAsStream("/pam-challenge.json")!!.bufferedReader().readText()).getJSONObject("challenge")
   .put("computer_fingerprint", "56".repeat(32)).put("device_id", service.get().credentials.id).put("expires", System.currentTimeMillis() / 1000 + 45)
  val replies = mutableListOf<String>()
  auth.receive(request, challenge, Any()) { replies.add(it) }; shadowOf(Looper.getMainLooper()).idle()
  val controller = Robolectric.buildActivity(PhoneAuthenticationActivity::class.java, Intent().putExtra("request", request)).setup()
  try {
   val activity = controller.get()
   assertTrue(activity.findViewById<View>(R.id.authentication_approve).isEnabled)
   assertTrue(activity.findViewById<TextView>(R.id.authentication_request).text.contains(challenge.getString("username")))
   assertEquals(Color.rgb(36, 36, 36), AdwaitaUi(activity).window)
   assertNull(ShadowBiometricPrompt.getCurrentPrompt())
   capture(activity, "fingerprint-review-dark")
   auth.cancel("Request ended"); shadowOf(Looper.getMainLooper()).idle()
   assertFalse(activity.findViewById<View>(R.id.authentication_approve).isEnabled)
   assertTrue(activity.findViewById<TextView>(R.id.authentication_request).text.contains("ended"))
   assertEquals(listOf(""), replies)
   activity.findViewById<View>(R.id.authentication_decline).performClick(); assertTrue(activity.isFinishing)
  } finally { controller.pause().stop().destroy(); service.destroy() }
 }
 @Test fun fingerprintSetupWrapsAtLargeTextAndUsesSavedDarkAppearance() {
  configure(dark = true, small = true)
  val service = Robolectric.buildService(CompanionService::class.java).create(); credential(service.get())
  val controller = Robolectric.buildActivity(PhoneAuthenticationActivity::class.java).setup()
  try {
   capture(controller.get(), "fingerprint-setup-large-text-dark"); assertTextFits(controller.get())
   controller.get().findViewById<View>(R.id.authentication_reset).performClick(); shadowOf(Looper.getMainLooper()).idle()
   val dialog = ShadowAlertDialog.getLatestAlertDialog()
   captureView(dialog.window!!.decorView, controller.get(), "fingerprint-reset-dialog-large-text-dark", wrapHeight = true)
   val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE); val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
   assertTrue("Dialog actions stack for large text", positive.top >= negative.bottom || negative.top >= positive.bottom)
   assertTrue(positive.height >= AdwaitaUi(controller.get()).dp(48))
   dialog.dismiss()
  } finally { controller.pause().stop().destroy(); service.destroy() }
 }
 @Test fun incomingAndActiveCallsRenderOfflineAndKeepTheKeypadAcrossRecreation() {
  configure(dark = true)
  val service = Robolectric.buildService(UnityInCallService::class.java).create()
  val details = Shadow.newInstanceOf(Call.Details::class.java)
  ReflectionHelpers.setField(details, "mHandle", Uri.parse("tel:15550123"))
  ReflectionHelpers.setField(details, "mCallerDisplayName", "Alex Morgan")
  ReflectionHelpers.setField(details, "mConnectTimeMillis", System.currentTimeMillis() - 45000)
  ReflectionHelpers.setField(details, "mCallCapabilities", Call.Details.CAPABILITY_HOLD)
  val call = Shadow.newInstanceOf(Call::class.java)
  ReflectionHelpers.setField(call, "mDetails", details); ReflectionHelpers.setField(call, "mState", Call.STATE_RINGING)
  ReflectionHelpers.setField(call, "mCallbackRecords", java.util.concurrent.CopyOnWriteArrayList<Any>())
  service.get().onCallAdded(call)
  val controller = Robolectric.buildActivity(PhoneCallActivity::class.java).setup()
  try {
   assertNull(CompanionService.instance)
   assertTrue(button(controller.get(), "Answer").isEnabled)
   capture(controller.get(), "phone-incoming-dark")
   ReflectionHelpers.setField(call, "mState", Call.STATE_ACTIVE); CompanionEvents.changed(); shadowOf(Looper.getMainLooper()).idle()
   button(controller.get(), "Keypad").performClick()
   assertEquals(12, views(root(controller.get())).count { it.contentDescription?.startsWith("Key ") == true })
   capture(controller.get(), "phone-call-dark")
   controller.recreate(); assertNotNull(button(controller.get(), "Hide keypad"))
   service.get().onCallRemoved(call); shadowOf(Looper.getMainLooper()).idle(); assertTrue(controller.get().isFinishing)
  } finally { controller.pause().stop().destroy(); service.destroy() }
 }
 @Test fun styledChoiceDialogKeepsNativeSelectionAndCancelSemantics() {
  configure(dark = true)
  val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
  try {
   val activity = controller.get(); activity.findViewById<View>(R.id.nav_settings).performClick()
   activity.findViewById<View>(R.id.connection_method).performClick()
   val dialog = ShadowAlertDialog.getLatestAlertDialog()
   assertEquals(0, dialog.listView.checkedItemPosition)
   dialog.listView.performItemClick(null, 2, 2)
   assertFalse(activity.findViewById<EditText>(R.id.computer_host).isEnabled)
   activity.findViewById<View>(R.id.connection_method).performClick()
   val selected = ShadowAlertDialog.getLatestAlertDialog(); assertEquals(2, selected.listView.checkedItemPosition)
   shadowOf(Looper.getMainLooper()).idle()
   assertNull(selected.getButton(AlertDialog.BUTTON_NEGATIVE).transformationMethod)
   captureView(selected.window!!.decorView, activity, "connection-dialog-dark", wrapHeight = true)
   selected.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
   assertFalse(activity.findViewById<EditText>(R.id.computer_host).isEnabled)
  } finally { controller.pause().stop().destroy() }
 }
}
