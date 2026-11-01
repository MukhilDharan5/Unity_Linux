package org.unityconnect.companion

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** Shared appearance, header, content clamp and safe areas for companion-owned screens. */
abstract class AdwaitaActivity : Activity() {
 override fun onWindowFocusChanged(hasFocus: Boolean) {
  super.onWindowFocusChanged(hasFocus)
  if (hasFocus) CompanionService.instance?.features?.clipboard?.publish()
 }
 override fun attachBaseContext(base: Context) {
  val config = Configuration(base.resources.configuration)
  when (base.getSharedPreferences("settings", MODE_PRIVATE).getString("appearance", "system")) {
   "light" -> config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
   "dark" -> config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
  }
  super.attachBaseContext(base.createConfigurationContext(config))
 }
 protected fun contentPage(title: String): LinearLayout {
  val ui = AdwaitaUi(this)
  val root = ui.column().apply { setBackgroundColor(ui.window) }
  val header = LinearLayout(this).apply {
   gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(ui.surface)
   setPadding(ui.dp(12), ui.dp(8), ui.dp(16), ui.dp(8))
  }
  header.addView(ui.iconButton(R.drawable.ic_back, "Back") { finish() }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
  header.addView(ui.label(title, 20f, true).apply { isAccessibilityHeading = true; setPadding(ui.dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
  root.addView(header); root.addView(ui.divider())
  val page = ClampedColumn(this).apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(8)) }
  val clamp = FrameLayout(this).apply { addView(page, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)) }
  root.addView(ScrollView(this).apply { isFillViewport = true; addView(clamp) }, LinearLayout.LayoutParams(-1, 0, 1f))
  setContentView(root); applyInsets(root)
  return page
 }
 @Suppress("DEPRECATION")
 protected fun applyInsets(root: View) {
  if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
  else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
  root.setOnApplyWindowInsetsListener { view, insets ->
   if (Build.VERSION.SDK_INT >= 30) {
    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    val ime = insets.getInsets(WindowInsets.Type.ime())
    view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
   } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
   insets
  }
  root.requestApplyInsets()
 }
}
