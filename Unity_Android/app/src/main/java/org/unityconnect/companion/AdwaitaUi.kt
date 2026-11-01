package org.unityconnect.companion

import android.content.Context
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
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
 val destructive = context.getColor(R.color.unity_destructive)
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
 fun styleButton(button: Button, suggested: Boolean = false, destructive: Boolean = false) = button.apply {
  isAllCaps = false; textSize = 15f; typeface = Typeface.create("sans-serif", Typeface.BOLD)
  setSingleLine(false); ellipsize = null
  val color = if (destructive) this@AdwaitaUi.destructive else accent
  setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, if (suggested || destructive) Color.WHITE else this@AdwaitaUi.text)))
  backgroundTintList = null; stateListAnimator = null; elevation = 0f
  background = StateListDrawable().apply {
   addState(intArrayOf(-android.R.attr.state_enabled), shape(context.getColor(R.color.unity_button)))
   addState(intArrayOf(), ripple(if (suggested || destructive) color else context.getColor(R.color.unity_button)))
  }
  minHeight = dp(48); minimumHeight = dp(48); minWidth = dp(48); minimumWidth = dp(48)
  setPadding(dp(18), dp(12), dp(18), dp(12))
 }
 fun button(title: String, suggested: Boolean = false, destructive: Boolean = false, action: () -> Unit) = styleButton(Button(context), suggested, destructive).apply {
  text = title; setOnClickListener { action() }
 }
 fun action(card: LinearLayout, title: String, suggested: Boolean = false, destructive: Boolean = false, click: () -> Unit): Button {
  val button = button(title, suggested, destructive, click)
  card.addView(button, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) })
  return button
 }
 fun iconButton(resource: Int, description: String, action: () -> Unit) = ImageButton(context).apply {
  setImageResource(resource); imageTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(border, text))
  contentDescription = description; tooltipText = description
  backgroundTintList = null; background = ripple(context.getColor(R.color.unity_button))
  minimumWidth = dp(48); minimumHeight = dp(48); setPadding(dp(12), dp(12), dp(12), dp(12))
  setOnClickListener { action() }
 }
 fun keypad(allowPlus: Boolean = true, pressed: (String) -> Unit) = column().apply {
  val letters = listOf("", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "+", "")
  "123456789*0#".chunked(3).forEachIndexed { row, digits ->
   val line = LinearLayout(context).apply { isBaselineAligned = false }
   digits.forEachIndexed { col, digit ->
    val caption = letters[row * 3 + col].let { if (digit == '0' && !allowPlus) "" else it }
    line.addView(button("$digit") { pressed(digit.toString()) }.apply {
     text = SpannableString("$digit\n${caption.ifEmpty { " " }}").apply {
      setSpan(RelativeSizeSpan(.45f), 2, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
     }
     textSize = 24f; contentDescription = "Key $digit"; minimumHeight = dp(64)
     setPadding(dp(4), dp(8), dp(4), dp(8))
     if (digit == '0' && allowPlus) setOnLongClickListener { pressed("+"); true }
    }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
   }
   addView(line)
  }
 }
 fun slider(title: String) = SeekBar(context).apply {
  contentDescription = title; minimumHeight = dp(48)
  progressTintList = ColorStateList.valueOf(accent); progressBackgroundTintList = ColorStateList.valueOf(border)
  thumbTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(border, accent))
  splitTrack = false
 }
 fun choices(values: List<String>, chevron: Boolean = false): ArrayAdapter<String> = object : ArrayAdapter<String>(context, 0, values) {
  override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = if (chevron) {
   row(getItem(position).orEmpty(), "", R.drawable.ic_media, icon(R.drawable.ic_chevron, size = 18)).view
  } else choice(getItem(position).orEmpty())
  override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = choice(getItem(position).orEmpty())
 }
 private fun choice(title: String) = CheckedTextView(context).apply {
  text = title; textSize = 16f; setTextColor(this@AdwaitaUi.text); gravity = Gravity.CENTER_VERTICAL
  minimumHeight = dp(56); setPadding(dp(20), dp(14), dp(20), dp(14))
  checkMarkDrawable = StateListDrawable().apply {
   addState(intArrayOf(android.R.attr.state_checked), context.getDrawable(R.drawable.ic_check)!!.mutate().apply { setTint(accent) })
   addState(intArrayOf(), shape(Color.TRANSPARENT).apply { setSize(dp(24), dp(24)) })
  }
  background = StateListDrawable().apply {
   addState(intArrayOf(android.R.attr.state_checked), ripple(accentSoft))
   addState(intArrayOf(), ripple(Color.TRANSPARENT))
  }
 }
 fun dialog(destructive: Boolean = false) = AdwaitaDialogBuilder(context, this, destructive)
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

/** Retains native dialog focus, dismissal and single-choice semantics with the app's styling. */
class AdwaitaDialogBuilder(context: Context, private val ui: AdwaitaUi, private val destructive: Boolean) : AlertDialog.Builder(context, R.style.Theme_Unity_Dialog) {
 override fun setTitle(title: CharSequence?): AlertDialog.Builder {
  super.setTitle(title)
  return super.setCustomTitle(ui.label(title?.toString().orEmpty(), 20f, true).apply {
   gravity = Gravity.CENTER; isAccessibilityHeading = true; setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(12))
  })
 }
 override fun setSingleChoiceItems(items: Array<out CharSequence>, checkedItem: Int, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
  super.setSingleChoiceItems(ui.choices(items.map { it.toString() }), checkedItem, listener)
 override fun setItems(items: Array<out CharSequence>, listener: DialogInterface.OnClickListener?): AlertDialog.Builder =
  super.setAdapter(ui.choices(items.map { it.toString() }), listener)
 override fun create(): AlertDialog = super.create().apply {
  window?.setBackgroundDrawable(InsetDrawable(ui.shape(ui.surface, 24), ui.dp(16)))
  setOnShowListener {
   window?.setLayout(minOf(ui.dp(560), context.resources.displayMetrics.widthPixels), ViewGroup.LayoutParams.WRAP_CONTENT)
   val buttons = listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).mapNotNull { getButton(it) }.filter { it.visibility == View.VISIBLE }
   val stacked = ui.compactActions || buttons.sumOf { it.paint.measureText(it.text.toString()).toInt() + ui.dp(48) } > minOf(ui.dp(480), context.resources.displayMetrics.widthPixels - ui.dp(80))
   (buttons.firstOrNull()?.parent as? LinearLayout)?.apply {
    minimumHeight = ui.dp(64); setPadding(paddingLeft, ui.dp(8), paddingRight, ui.dp(8))
   }
   if (stacked) (buttons.firstOrNull()?.parent as? LinearLayout)?.apply {
    orientation = LinearLayout.VERTICAL
    for (index in 0 until childCount) if (getChildAt(index) !is Button) getChildAt(index).visibility = View.GONE
   }
   for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
    getButton(which)?.let { button ->
     ui.styleButton(button, suggested = which == AlertDialog.BUTTON_POSITIVE, destructive = destructive && which == AlertDialog.BUTTON_POSITIVE)
     (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
      params.setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)); params.height = ViewGroup.LayoutParams.WRAP_CONTENT
      if (stacked) { params.width = ViewGroup.LayoutParams.MATCH_PARENT; (params as? LinearLayout.LayoutParams)?.weight = 0f }
      button.layoutParams = params
     }
    }
   }
   listView?.apply { divider = null; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)) }
  }
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
