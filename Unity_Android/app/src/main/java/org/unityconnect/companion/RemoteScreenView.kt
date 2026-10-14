package org.unityconnect.companion

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/** Normalize only image pixels: tapping the letterbox must never click the desktop. */
internal fun screenPoint(width: Int, height: Int, imageWidth: Int, imageHeight: Int, x: Float, y: Float, clamp: Boolean = false): Pair<Float, Float>? {
 if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0 || !x.isFinite() || !y.isFinite()) return null
 val scale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
 val drawnWidth = imageWidth * scale; val drawnHeight = imageHeight * scale
 val nx = (x - (width - drawnWidth) / 2) / drawnWidth; val ny = (y - (height - drawnHeight) / 2) / drawnHeight
 if (!clamp && (nx !in 0f..1f || ny !in 0f..1f)) return null
 return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
}

/** The selected Android IME targets this view; no accessibility or custom IME grant. */
class RemoteScreenView(context: Context) : ImageView(context) {
 var allowed: () -> Boolean = { false }
 var send: (JSONObject) -> Unit = {}
 var imageSize: () -> Pair<Int, Int>? = { null }
 var keyboardChanged: (Boolean) -> Unit = {}
 var keyboardVisible = false
  private set
 private val composing = SpannableStringBuilder()
 private var held = false
 private var scrolling = false
 private var scrollX = 0f; private var scrollY = 0f
 private var lastPoint = 0f to 0f
 private var lastMotion = 0L
 init { scaleType = ScaleType.FIT_CENTER; isFocusable = true; isFocusableInTouchMode = true }
 private fun point(event: MotionEvent, clamp: Boolean = false): Pair<Float, Float>? {
  val image = imageSize() ?: return null
  return screenPoint(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom, image.first, image.second,
   event.x - paddingLeft, event.y - paddingTop, clamp)
 }
 private fun pointer(point: Pair<Float, Float>, pressed: Boolean) {
  send(JSONObject().put("kind", "pointer").put("x", point.first).put("y", point.second).put("button", "left").put("pressed", pressed))
 }
 override fun onTouchEvent(event: MotionEvent): Boolean {
  if (!allowed()) { resetInput(); return false }
  when (event.actionMasked) {
   MotionEvent.ACTION_DOWN -> {
    val position = point(event) ?: return false
    requestFocus(); parent?.requestDisallowInterceptTouchEvent(true)
    lastPoint = position; held = true; scrolling = false; pointer(position, true); return true
   }
   MotionEvent.ACTION_POINTER_DOWN -> {
    if (held) { pointer(lastPoint, false); held = false }
    scrolling = event.pointerCount >= 2
    scrollX = event.getX(0); scrollY = event.getY(0); return true
   }
   MotionEvent.ACTION_MOVE -> {
    if (scrolling && event.pointerCount >= 2) {
     if (event.eventTime - lastMotion >= 40) {
      val dx = (scrollX - event.getX(0)).coerceIn(-1000f, 1000f); val dy = (scrollY - event.getY(0)).coerceIn(-1000f, 1000f)
      scrollX = event.getX(0); scrollY = event.getY(0); lastMotion = event.eventTime
      send(JSONObject().put("kind", "scroll").put("dx", dx).put("dy", dy))
     }
    } else if (held) {
     point(event, true)?.let { position ->
      lastPoint = position
      if (event.eventTime - lastMotion >= 40) { lastMotion = event.eventTime; send(JSONObject().put("kind", "motion").put("x", position.first).put("y", position.second)) }
     }
    }
    return held || scrolling
   }
   MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
    if (held) { point(event, true)?.let { lastPoint = it }; pointer(lastPoint, false) }
    held = false; scrolling = false; parent?.requestDisallowInterceptTouchEvent(false)
    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
    return true
   }
  }
  return held || scrolling
 }
 override fun performClick(): Boolean { super.performClick(); return true }
 fun resetInput() { if (held && allowed()) pointer(lastPoint, false); held = false; scrolling = false; composing.clear(); if (keyboardVisible) showKeyboard(false, false) }
 fun showKeyboard(show: Boolean, commit: Boolean = true) {
  val visible = show && allowed()
  if (!visible && commit && allowed() && composing.isNotEmpty()) text(composing.toString())
  composing.clear(); keyboardVisible = visible; keyboardChanged(visible)
  val keyboard = context.getSystemService(InputMethodManager::class.java)
  if (visible) { requestFocus(); keyboard.restartInput(this); post { if (keyboardVisible) keyboard.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) } }
  else keyboard.hideSoftInputFromWindow(windowToken, 0)
 }
 fun keyboardHiddenBySystem() { if (keyboardVisible) { if (composing.isNotEmpty()) text(composing.toString()); keyboardVisible = false; composing.clear(); keyboardChanged(false) } }
 override fun onCheckIsTextEditor() = keyboardVisible && allowed()
 private fun text(value: String) {
  if (!allowed()) return
  var part = StringBuilder()
  value.codePoints().forEach { code ->
   val character = String(Character.toChars(code))
   if ((part.toString() + character).toByteArray(Charsets.UTF_8).size > 256) { send(JSONObject().put("kind", "text").put("text", part.toString())); part = StringBuilder() }
   part.append(character)
  }
  if (part.isNotEmpty()) send(JSONObject().put("kind", "text").put("text", part.toString()))
 }
 private fun key(name: String, repeat: Int = 1, modifiers: JSONArray = JSONArray()) {
  if (allowed()) send(JSONObject().put("kind", "key").put("key", name).put("repeat", repeat.coerceIn(1, 32)).put("modifiers", modifiers))
 }
 private fun eventKey(event: KeyEvent): Boolean {
  if (!allowed()) return false
  if (event.action != KeyEvent.ACTION_DOWN) return true
  val name = when (event.keyCode) {
   KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
   KeyEvent.KEYCODE_DEL -> "backspace"
   KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
   KeyEvent.KEYCODE_TAB -> "tab"
   KeyEvent.KEYCODE_ESCAPE -> "escape"
   KeyEvent.KEYCODE_DPAD_LEFT -> "left"
   KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
   KeyEvent.KEYCODE_DPAD_UP -> "up"
   KeyEvent.KEYCODE_DPAD_DOWN -> "down"
   KeyEvent.KEYCODE_MOVE_HOME -> "home"
   KeyEvent.KEYCODE_MOVE_END -> "end"
   KeyEvent.KEYCODE_PAGE_UP -> "page_up"
   KeyEvent.KEYCODE_PAGE_DOWN -> "page_down"
   else -> null
  }
  val modifiers = JSONArray().apply {
   if (event.isCtrlPressed) put("ctrl"); if (event.isAltPressed) put("alt"); if (event.isShiftPressed) put("shift"); if (event.isMetaPressed) put("super")
  }
  if (name != null) { key(name, 1, modifiers); return true }
  val plain = event.getUnicodeChar(0)
  if ((event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) && plain.toChar().isLetterOrDigit() && plain in 32..126) {
   key(plain.toChar().toString(), 1, modifiers); return true
  }
  val code = event.unicodeChar
  if (code > 0 && Character.isValidCodePoint(code)) { text(String(Character.toChars(code))); return true }
  return false
 }
 override fun onKeyDown(code: Int, event: KeyEvent) = eventKey(event) || super.onKeyDown(code, event)
 override fun onCreateInputConnection(info: EditorInfo): InputConnection? {
  if (!onCheckIsTextEditor()) return null
  info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
  info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_NONE
  return object : BaseInputConnection(this, false) {
   override fun getEditable(): Editable = composing
   override fun commitText(value: CharSequence?, position: Int): Boolean { composing.clear(); text(value?.toString().orEmpty()); return true }
   override fun setComposingText(value: CharSequence?, position: Int): Boolean { composing.replace(0, composing.length, value ?: ""); Selection.setSelection(composing, composing.length); return true }
   override fun finishComposingText(): Boolean { val value = composing.toString(); composing.clear(); text(value); return true }
   override fun deleteSurroundingText(before: Int, after: Int): Boolean {
    if (composing.isNotEmpty()) { composing.delete((composing.length - before.coerceAtLeast(0)).coerceAtLeast(0), composing.length); Selection.setSelection(composing, composing.length) }
    else { if (before > 0) key("backspace", before); if (after > 0) key("delete", after) }
    return true
   }
   override fun deleteSurroundingTextInCodePoints(before: Int, after: Int): Boolean {
    if (composing.isEmpty()) return deleteSurroundingText(before, after)
    val count = Character.codePointCount(composing, 0, composing.length)
    val start = Character.offsetByCodePoints(composing, composing.length, -before.coerceIn(0, count))
    composing.delete(start, composing.length); Selection.setSelection(composing, composing.length); return true
   }
   override fun sendKeyEvent(event: KeyEvent) = eventKey(event)
   override fun performEditorAction(action: Int): Boolean { key("enter"); return true }
  }
 }
}
