package org.unityconnect.companion

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Native-Views style layer, independent of transports and feature handlers. */
class AdwaitaUi(private val context: Context) {
 val window = context.getColor(R.color.unity_window)
 val surface = context.getColor(R.color.unity_surface)
 val sidebar = context.getColor(R.color.unity_sidebar)
 val text = context.getColor(R.color.unity_text)
 val muted = context.getColor(R.color.unity_muted)
 val border = context.getColor(R.color.unity_border)
 val accent = context.getColor(R.color.unity_accent)
 val accentText = context.getColor(R.color.unity_accent_text)
 val accentSoft = context.getColor(R.color.unity_accent_soft)
 val success = context.getColor(R.color.unity_success)
 val successSoft = context.getColor(R.color.unity_success_soft)
 val compactActions = context.resources.configuration.fontScale > 1.3f || context.resources.configuration.screenWidthDp < 360
 fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
 fun shape(color: Int, radius: Int = 12, outlined: Boolean = false) = GradientDrawable().apply {
  setColor(color); cornerRadius = dp(radius).toFloat()
  if (outlined) setStroke(dp(1), border)
 }
 fun ripple(color: Int, radius: Int = 12) = RippleDrawable(ColorStateList.valueOf(accentSoft), shape(color, radius), shape(Color.WHITE, radius))
 fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
 fun label(value: String, size: Float = 16f, bold: Boolean = false, secondary: Boolean = false) = TextView(context).apply {
  text = value; textSize = size; setTextColor(if (secondary) muted else this@AdwaitaUi.text)
  if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
  includeFontPadding = false; setLineSpacing(dp(2).toFloat(), 1f)
 }
 fun icon(resource: Int, color: Int = muted, size: Int = 24) = ImageView(context).apply {
  setImageResource(resource); imageTintList = ColorStateList.valueOf(color)
  layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
  importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
 }
 fun divider(color: Int = border) = View(context).apply {
  setBackgroundColor(color); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
  importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
 }
 fun section(page: LinearLayout, title: String, description: String = ""): LinearLayout {
  val group = column().apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) } }
  group.addView(label(title, 16f, true).apply { isAccessibilityHeading = true; setPadding(dp(4), 0, dp(4), dp(12)) })
  if (description.isNotBlank()) group.addView(label(description, 14f, secondary = true).apply { setPadding(dp(4), 0, dp(4), dp(12)) })
  val card = column().apply { background = shape(surface, 16, true); clipToOutline = true; elevation = dp(1).toFloat() }
  group.addView(card); page.addView(group); return card
 }
 fun addRow(card: LinearLayout, child: View) { if (card.childCount > 0) card.addView(divider()); card.addView(child) }
 data class Row(val view: LinearLayout, val title: TextView, val subtitle: TextView)
 fun row(title: String, description: String, drawable: Int, suffix: View? = null, action: (() -> Unit)? = null): Row {
  val row = LinearLayout(context).apply {
   orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
   setPadding(dp(16), dp(14), dp(16), dp(14)); minimumHeight = dp(72)
  }
  row.addView(icon(drawable).apply { layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) } })
  val titleView = label(title)
  val descriptionView = label(description, 13f, secondary = true).apply {
   visibility = if (description.isBlank()) View.GONE else View.VISIBLE; setPadding(0, dp(5), 0, 0)
  }
  row.addView(column().apply { addView(titleView); addView(descriptionView) }, LinearLayout.LayoutParams(0, -2, 1f))
  if (suffix != null) row.addView(suffix, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
  else if (action != null) row.addView(icon(R.drawable.ic_chevron, size = 18).apply { layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(12) } })
  if (action != null) { row.background = ripple(Color.TRANSPARENT, 0); row.isFocusable = true; row.setOnClickListener { action() } }
  return Row(row, titleView, descriptionView)
 }
 fun describe(row: Row, value: String) { row.subtitle.text = value; row.subtitle.visibility = if (value.isBlank()) View.GONE else View.VISIBLE }
 fun button(title: String, suggested: Boolean = false, action: () -> Unit) = Button(context).apply {
  text = title; isAllCaps = false; textSize = 15f; typeface = Typeface.create("sans-serif", Typeface.BOLD)
  setTextColor(if (suggested) Color.WHITE else this@AdwaitaUi.text)
  background = ripple(if (suggested) accent else context.getColor(R.color.unity_button))
  minHeight = dp(48); minimumHeight = dp(48); minWidth = dp(48); minimumWidth = dp(48)
  setPadding(dp(18), dp(12), dp(18), dp(12)); setOnClickListener { action() }
 }
 fun toggle(checked: Boolean, title: String, changed: (Boolean) -> Unit) = Switch(context).apply {
  isChecked = checked; showText = false; switchMinWidth = dp(48); minimumHeight = dp(48)
  thumbDrawable = shape(Color.WHITE, 20).apply { setSize(dp(24), dp(24)); setStroke(dp(1), border) }
  trackDrawable = shape(border, 20).apply { setSize(dp(48), dp(28)) }
  contentDescription = title; thumbTintList = ColorStateList.valueOf(Color.WHITE)
  trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, border))
  setOnCheckedChangeListener { _, value -> changed(value) }
 }
 fun field(card: LinearLayout, title: String, value: String, id: Int, input: Int = InputType.TYPE_CLASS_TEXT): EditText {
  val box = column().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
  box.addView(label(title, 13f, true).apply { labelFor = id; setPadding(0, 0, 0, dp(8)) })
  val field = EditText(context).apply {
   this.id = id; setText(value); textSize = 16f; inputType = input
   setTextColor(this@AdwaitaUi.text); setHintTextColor(muted); background = shape(window, 10, true)
   setPadding(dp(12), dp(12), dp(12), dp(12)); minimumHeight = dp(48)
   importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO; setSelectAllOnFocus(true)
  }
  box.addView(field, LinearLayout.LayoutParams(-1, -2)); addRow(card, box); return field
 }
 fun navItem(title: String, drawable: Int, id: Int, wide: Boolean, action: () -> Unit): LinearLayout {
  val item = LinearLayout(context).apply {
   this.id = id; orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
   gravity = if (wide) Gravity.CENTER_VERTICAL else Gravity.CENTER
   minimumHeight = dp(if (wide || compactActions) 56 else 72); setPadding(dp(8), dp(10), dp(8), dp(10))
   isFocusable = true; contentDescription = title; setOnClickListener { action() }
  }
  item.addView(icon(drawable).apply { layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { if (wide) marginEnd = dp(12) else bottomMargin = dp(5) } })
  item.addView(label(title, if (wide) 15f else 12f).apply {
   gravity = if (wide) Gravity.START else Gravity.CENTER
   if (!wide && compactActions) visibility = View.GONE
  })
  return item
 }
 fun select(item: LinearLayout, selected: Boolean) {
  item.isSelected = selected; item.background = ripple(if (selected) accentSoft else Color.TRANSPARENT)
  (item.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(if (selected) accentText else muted)
  (item.getChildAt(1) as TextView).setTextColor(if (selected) accentText else muted)
 }
}

/** AdwClamp-like content sizing for wide windows. */
class ClampedColumn(context: Context) : LinearLayout(context) {
 init { orientation = VERTICAL }
 override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
  val width = minOf(MeasureSpec.getSize(widthMeasureSpec), (720 * resources.displayMetrics.density).toInt())
  super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
 }
}
