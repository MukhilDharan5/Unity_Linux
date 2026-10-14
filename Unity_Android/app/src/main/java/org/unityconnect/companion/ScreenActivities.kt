package org.unityconnect.companion

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.os.Build
import android.widget.Button
import android.widget.ImageView
import android.widget.ImageButton
import android.content.res.ColorStateList
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

class ScreenViewerActivity : Activity() {
 private var requestId: String? = null
 private lateinit var picture: RemoteScreenView
 private lateinit var status: TextView
 private lateinit var keyboard: ImageButton
 private lateinit var display: ImageButton
 private var imeWasVisible = false
 private val changed: () -> Unit = { render() }
 override fun onCreate(saved: Bundle?) {
  super.onCreate(saved)
  val screen = CompanionService.instance?.features?.screen
  requestId = intent.getStringExtra("screen_request") ?: screen?.currentId()
  if (intent.hasExtra("screen_request") && (requestId != screen?.currentId() || screen?.source() != "desktop")) { finish(); return }
  screen?.viewerOpened()
  val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
  status = TextView(this).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(16, 24, 16, 16) }
  picture = RemoteScreenView(this).apply {
   id = R.id.computer_screen_view; contentDescription = "Live computer screen. Touch to click and drag; use two fingers to scroll."
   allowed = { requestId != null && CompanionService.instance?.features?.screen?.currentId() == requestId && CompanionService.instance?.features?.screen?.canControl() == true }
   imageSize = { CompanionService.instance?.features?.screen?.bitmap?.let { it.width to it.height } }
   send = { data -> CompanionService.instance?.features?.screen?.input(data) }
   keyboardChanged = { shown -> if (::keyboard.isInitialized) { keyboard.contentDescription = if (shown) "Hide keyboard" else "Show keyboard"; keyboard.isActivated = shown } }
  }
  fun icon(resource: Int, description: String, identity: Int, click: () -> Unit) = ImageButton(this).apply {
   id = identity; setImageResource(resource); imageTintList = ColorStateList.valueOf(Color.WHITE)
   contentDescription = description; setOnClickListener { click() }
   backgroundTintList = ColorStateList.valueOf(Color.rgb(45,45,45))
  }
  keyboard = icon(R.drawable.ic_keyboard, "Show keyboard", R.id.screen_keyboard_toggle) { picture.showKeyboard(!picture.keyboardVisible) }
  display = icon(R.drawable.ic_computer, "Turn off laptop display", R.id.screen_display_toggle) { CompanionService.instance?.features?.screen?.let { it.setDisplayOn(!it.displayOn) } }
  val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
  toolbar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
  val iconSize = (48 * resources.displayMetrics.density).toInt()
  toolbar.addView(display, LinearLayout.LayoutParams(iconSize, iconSize)); toolbar.addView(keyboard, LinearLayout.LayoutParams(iconSize, iconSize))
  val stop = Button(this).apply { setText(R.string.stop_screen_sharing); setOnClickListener { stopCurrent(); finish() } }
  root.addView(toolbar); root.addView(picture, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(stop)
  root.setOnApplyWindowInsetsListener { view, insets ->
   if (Build.VERSION.SDK_INT >= 30) {
    val bars = insets.getInsets(WindowInsets.Type.systemBars()); val ime = insets.getInsets(WindowInsets.Type.ime())
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
  setContentView(root); root.requestApplyInsets(); render()
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
   screen.state == "streaming" -> if (!screen.displayOn) "Live · Laptop display off" else if (control) "Live · Touch to control" else "Computer screen · View only"
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
