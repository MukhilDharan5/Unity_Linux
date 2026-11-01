package org.unityconnect.companion

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.os.Build
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

class ScreenConsentActivity : Activity() {
 override fun onCreate(saved: Bundle?) {
  super.onCreate(saved)
  val id = intent.getStringExtra("screen_request")
  if (id == null || CompanionService.instance?.features?.screen?.pendingId() != id) { finish(); return }
  if (saved == null) startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 710)
 }
 @Deprecated("Platform result callback")
 override fun onActivityResult(request: Int, result: Int, data: Intent?) {
  super.onActivityResult(request, result, data)
  if (request == 710) { CompanionService.instance?.features?.screen?.consent(intent.getStringExtra("screen_request"), result, data); finish() }
 }
}

class ScreenViewerActivity : AdwaitaActivity() {
 private var requestId: String? = null
 private lateinit var picture: RemoteScreenView
 private lateinit var status: TextView
 private lateinit var keyboard: ImageButton
 private lateinit var display: ImageButton
 private lateinit var rotate: ImageButton
 private var imeWasVisible = false
 private val changed: () -> Unit = { render() }
 override fun onCreate(saved: Bundle?) {
  super.onCreate(saved)
  val screen = CompanionService.instance?.features?.screen
  requestId = intent.getStringExtra("screen_request") ?: screen?.currentId()
  if (intent.hasExtra("screen_request") && (requestId != screen?.currentId() || screen?.source() != "desktop")) { finish(); return }
  requestedOrientation = saved?.getInt("viewer_orientation", ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR) ?: ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
  window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  screen?.viewerOpened()
  val ui = AdwaitaUi(this)
  val root = ui.column().apply { setBackgroundColor(ui.window) }
  status = ui.label("", 13f, secondary = true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(12)) }
  picture = RemoteScreenView(this).apply {
   id = R.id.computer_screen_view; contentDescription = "Live computer screen. Touch to click and drag; use two fingers to scroll."
   allowed = { requestId != null && CompanionService.instance?.features?.screen?.currentId() == requestId && CompanionService.instance?.features?.screen?.canControl() == true }
   imageSize = { CompanionService.instance?.features?.screen?.bitmap?.let { it.width to it.height } }
   send = { data -> CompanionService.instance?.features?.screen?.input(data) }
   keyboardChanged = { shown -> if (::keyboard.isInitialized) { keyboard.contentDescription = if (shown) "Hide keyboard" else "Show keyboard"; keyboard.isActivated = shown } }
  }
  fun icon(resource: Int, description: String, identity: Int, click: () -> Unit) = ui.iconButton(resource, description, click).apply { id = identity }
  keyboard = icon(R.drawable.ic_keyboard, "Show keyboard", R.id.screen_keyboard_toggle) { picture.showKeyboard(!picture.keyboardVisible) }
  display = icon(R.drawable.ic_computer, "Turn off laptop display", R.id.screen_display_toggle) { CompanionService.instance?.features?.screen?.let { it.setDisplayOn(!it.displayOn) } }
  rotate = icon(R.drawable.ic_rotate, "Use landscape", R.id.screen_orientation_toggle) {
   picture.resetInput()
   requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
  }
  rotate.tooltipText = "Rotate · Hold for automatic rotation"
  rotate.setOnLongClickListener { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR; true }
  val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(ui.surface); setPadding(ui.dp(8),ui.dp(4),ui.dp(8),ui.dp(4)) }
  toolbar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
  val iconSize = (48 * resources.displayMetrics.density).toInt()
  toolbar.addView(rotate, LinearLayout.LayoutParams(iconSize, iconSize)); toolbar.addView(display, LinearLayout.LayoutParams(iconSize, iconSize)); toolbar.addView(keyboard, LinearLayout.LayoutParams(iconSize, iconSize))
  val stop = ui.button(getString(R.string.stop_screen_sharing), destructive = true) { stopCurrent(); finish() }
  root.addView(toolbar); root.addView(ui.divider()); root.addView(picture.apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, 0, 1f))
  root.addView(stop, LinearLayout.LayoutParams(-1,-2).apply { setMargins(ui.dp(16),ui.dp(12),ui.dp(16),ui.dp(12)) })
  root.setOnApplyWindowInsetsListener { view, insets ->
   if (Build.VERSION.SDK_INT >= 30) {
    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()); val ime = insets.getInsets(WindowInsets.Type.ime())
    view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
    val shown = insets.isVisible(WindowInsets.Type.ime())
    if (imeWasVisible && !shown) picture.keyboardHiddenBySystem()
    imeWasVisible = shown
   } else {
    @Suppress("DEPRECATION")
    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
   }
   insets
  }
  window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
  if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
  else @Suppress("DEPRECATION") run { window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION }
  setContentView(root); root.requestApplyInsets(); updateOrientation(); render()
 }
 private fun updateOrientation() {
  if (!::rotate.isInitialized) return
  val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
  rotate.contentDescription = if (landscape) "Use portrait" else "Use landscape"
  val padding = (if (landscape) 4 else 12) * resources.displayMetrics.density
  status.setPadding(8, padding.toInt(), 8, padding.toInt())
 }
 override fun onConfigurationChanged(configuration: Configuration) {
  super.onConfigurationChanged(configuration)
  if (::picture.isInitialized) { picture.resetInput(); picture.requestLayout() }
  updateOrientation(); render()
 }
 override fun onSaveInstanceState(state: Bundle) {
  state.putInt("viewer_orientation", requestedOrientation); super.onSaveInstanceState(state)
 }
 private fun stopCurrent() {
  val screen = CompanionService.instance?.features?.screen
  if (requestId != null && requestId == screen?.currentId()) screen?.stopRemote()
 }
 private fun render() {
  if (!::picture.isInitialized) return
  val screen = CompanionService.instance?.features?.screen
  val current = screen?.currentId() == requestId
  picture.setImageBitmap(if (current) screen?.bitmap else null)
  val control = current && screen?.canControl() == true
  keyboard.isEnabled = control
  display.isEnabled = current && screen?.state == "streaming" && screen.displayAvailable && (control || !screen.displayOn)
  display.contentDescription = if (screen?.displayOn == false) "Turn on laptop display" else "Turn off laptop display"
  if (!control) picture.resetInput()
  status.text = when {
   screen == null || !screen.active() || screen.currentId() != requestId -> screen?.lastError?.ifBlank { "Screen sharing ended" } ?: "Disconnected"
   screen.controlError.isNotBlank() -> screen.controlError
   screen.state == "streaming" -> if (screen.extendedDisplay) "Live · Extended display" else if (screen.phonePrimary) "Live · Phone is main display" else if (!screen.displayOn) "Live · Laptop display off" else if (control) "Live · Touch to control" else "Computer screen · View only"
   else -> "Choose a screen or window on your computer…"
  }
 }
 override fun onStart() { super.onStart(); CompanionService.instance?.features?.screen?.viewerVisibility(requestId, this, true); CompanionEvents.observe(changed); render() }
 override fun onStop() { CompanionService.instance?.features?.screen?.viewerVisibility(requestId, this, false); if (::picture.isInitialized) picture.resetInput(); CompanionEvents.remove(changed); super.onStop() }
 override fun onDestroy() {
  if (::picture.isInitialized) picture.resetInput()
  if (::picture.isInitialized) picture.setImageDrawable(null)
  if (requestId != null && requestId == CompanionService.instance?.features?.screen?.currentId() && isFinishing && !isChangingConfigurations) CompanionService.instance?.features?.screen?.stopRemote()
  super.onDestroy()
 }
}
