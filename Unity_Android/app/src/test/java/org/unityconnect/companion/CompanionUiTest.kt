package org.unityconnect.companion

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/** Real Android layouts and graphics; fixtures are confined to test code. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionUiTest {
 private var controller: ActivityController<MainActivity>? = null
 @After fun close() { controller?.pause()?.stop()?.destroy(); CompanionService.instance = null }
 private fun open(qualifiers: String = "w393dp-h852dp-notnight-xhdpi", fontScale: Float = 1f): MainActivity {
  RuntimeEnvironment.setQualifiers(qualifiers)
  RuntimeEnvironment.setFontScale(fontScale)
  controller = Robolectric.buildActivity(MainActivity::class.java).setup()
  return controller!!.get()
 }
 private fun root(activity: MainActivity) = activity.findViewById<View>(R.id.unity_root)
 private fun layout(activity: MainActivity) {
  val metrics = activity.resources.displayMetrics
  val width = (activity.resources.configuration.screenWidthDp * metrics.density).toInt()
  val height = (activity.resources.configuration.screenHeightDp * metrics.density).toInt()
  root(activity).measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
  root(activity).layout(0, 0, width, height)
 }
 private fun capture(activity: MainActivity, name: String) {
  layout(activity)
  val view = root(activity)
  val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
  view.draw(Canvas(bitmap))
  val out = File("build/reports/unity-preview/$name.png"); out.parentFile!!.mkdirs()
  out.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
  bitmap.recycle()
 }
 private fun texts(view: View): List<TextView> = when (view) {
  is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
  is TextView -> listOf(view)
  else -> emptyList()
 }
 @Test fun lightPagesHaveRealEmptyStatesAndNoEnabledRemoteActions() {
  val activity = open()
  assertEquals("Not connected", activity.findViewById<TextView>(R.id.connection_title).text.toString())
  assertFalse(activity.findViewById<View>(R.id.send_file_button).isEnabled)
  assertFalse(activity.findViewById<View>(R.id.send_clipboard_button).isEnabled)
  assertFalse(activity.findViewById<View>(R.id.received_url_button).isEnabled)
  assertFalse(activity.findViewById<Switch>(R.id.feature_notifications).isChecked)
  listOf("overview" to R.id.nav_overview, "sharing" to R.id.nav_sharing, "features" to R.id.nav_features, "settings" to R.id.nav_settings).forEach { (name, id) ->
   activity.findViewById<View>(id).performClick(); capture(activity, "$name-light")
   assertTrue(activity.findViewById<View>(id).isSelected)
  }
 }
 @Test fun darkStyleAndTabletNavigationRenderNatively() {
  val activity = open("w1024dp-h800dp-night-xhdpi")
  activity.findViewById<View>(R.id.nav_features).performClick(); capture(activity, "features-tablet-dark")
  assertEquals(activity.getColor(R.color.unity_window), AdwaitaUi(activity).window)
  assertTrue(activity.findViewById<View>(R.id.nav_features).width > activity.resources.displayMetrics.density * 150)
 }
 @Test fun explicitDarkAppearanceOverridesLightSystemAndRendersOnPhone() {
  RuntimeEnvironment.getApplication().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("appearance", "dark").commit()
  val activity = open()
  assertEquals(Color.rgb(36, 36, 36), AdwaitaUi(activity).window)
  capture(activity, "overview-dark")
 }
 @Test fun smallScreenAndLargeFontsDoNotClipControlsOrTextHorizontally() {
  val activity = open("w320dp-h740dp-notnight-xhdpi", 1.6f)
  listOf("features" to R.id.nav_features, "settings" to R.id.nav_settings).forEach { (name, id) ->
   activity.findViewById<View>(id).performClick(); capture(activity, "$name-large-text")
   val visiblePage = activity.findViewById<View>(if (name == "features") R.id.page_features else R.id.page_settings)
   texts(visiblePage).filter { it !is EditText && it.visibility == View.VISIBLE && it.text.isNotEmpty() }.forEach { text ->
    assertTrue("No horizontal truncation: " + text.text, text.layout != null)
    // getLineMax measures visible content; getLineWidth also counts trailing
    // wrapping spaces that Android deliberately paints outside neither edge.
    for (line in 0 until text.layout.lineCount) assertTrue("Text exceeds its container: " + text.text, text.layout.getLineMax(line) <= text.width - text.paddingLeft - text.paddingRight + 2)
   }
  }
 }
 @Test fun navigationAndRotationPreserveUnsavedPairingFields() {
  val activity = open()
  activity.findViewById<View>(R.id.nav_settings).performClick()
  activity.findViewById<EditText>(R.id.computer_host).setText("192.168.50.20")
  activity.findViewById<EditText>(R.id.computer_fingerprint).setText("ab".repeat(32))
  activity.findViewById<View>(R.id.nav_overview).performClick()
  activity.findViewById<View>(R.id.nav_settings).performClick()
  val saved = Bundle(); controller!!.saveInstanceState(saved).pause().stop().destroy(); controller = null
  controller = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().restoreInstanceState(saved).resume().visible()
  val restored = controller!!.get()
  assertEquals("192.168.50.20", restored.findViewById<EditText>(R.id.computer_host).text.toString())
  assertEquals("ab".repeat(32), restored.findViewById<EditText>(R.id.computer_fingerprint).text.toString())
  assertEquals(View.VISIBLE, restored.findViewById<View>(R.id.page_settings).visibility)
 }
 @Test fun featureConsentPersistsAndCancelDoesNotForgetComputer() {
  val activity = open()
  val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
  activity.findViewById<Switch>(R.id.feature_sms).isChecked = true
  assertTrue(prefs.getBoolean("enable.sms", false))
  prefs.edit().putString("host", "192.168.50.20").putString("fingerprint", "ab".repeat(32)).commit()
  activity.findViewById<View>(R.id.forget_computer_row).performClick()
  ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
  assertEquals("192.168.50.20", prefs.getString("host", null))
 }
 @Test fun pendingClipboardChangesArriveWithoutPollingAndCanBeAppliedOffline() {
  val activity = open()
  val pending = activity.getSharedPreferences("pending", Context.MODE_PRIVATE)
  pending.edit().putString("clipboard", "Explicit test text").commit()
  shadowOf(Looper.getMainLooper()).idle()
  val apply = activity.findViewById<View>(R.id.received_clipboard_button)
  assertTrue(apply.isEnabled); apply.performClick()
  assertFalse(pending.contains("clipboard"))
  val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
  assertEquals("Explicit test text", clipboard.primaryClip!!.getItemAt(0).text.toString())
 }
 @Test fun systemBarsAndKeyboardInsetsKeepControlsAboveOverlays() {
  val activity = open(); val view = root(activity)
  view.dispatchApplyWindowInsets(WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 48, 0, 40)).setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 450)).build())
  assertEquals(48, view.paddingTop); assertEquals(450, view.paddingBottom)
 }
 @Test fun connectionEventsUpdateStateAndClosingTheScreenLeavesServiceRunning() {
  val activity = open()
  val serviceController = Robolectric.buildService(CompanionService::class.java).create()
  val service = serviceController.get()
  try {
   activity.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("host", "laptop.test").putBoolean("enable.files", true).commit()
   // Explicit test fixture: no socket is opened or production data seeded.
   service.connection.ready = true
   service.status("Test connection state", ConnectionPhase.CONNECTED)
   shadowOf(Looper.getMainLooper()).idle()
   assertEquals("Connected securely", activity.findViewById<TextView>(R.id.connection_title).text.toString())
   assertTrue(activity.findViewById<View>(R.id.send_file_button).isEnabled)
   capture(activity, "overview-connected-test")
   service.connection.ready = false
   service.status("Test disconnection", ConnectionPhase.RECONNECTING)
   shadowOf(Looper.getMainLooper()).idle()
   assertEquals("Reconnecting…", activity.findViewById<TextView>(R.id.connection_title).text.toString())
   assertFalse(activity.findViewById<View>(R.id.send_file_button).isEnabled)
   controller!!.pause().stop().destroy(); controller = null
   assertSame(service, CompanionService.instance)
  } finally { serviceController.destroy(); shadowOf(Looper.getMainLooper()).idle() }
 }
 @Test fun recreationDoesNotResendAndroidShareIntent() {
  val activity = open(); val saved = Bundle(); controller!!.saveInstanceState(saved).pause().stop().destroy(); controller = null
  val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://example.test/test")
  controller = Robolectric.buildActivity(MainActivity::class.java, intent).create(saved).start().resume()
  assertEquals("", controller!!.get().findViewById<EditText>(R.id.share_url).text.toString())
 }
}
