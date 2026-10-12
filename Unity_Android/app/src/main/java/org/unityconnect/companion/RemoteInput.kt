package org.unityconnect.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class UnityKeyboard : InputMethodService() {
 companion object { @Volatile var instance: UnityKeyboard? = null }
 override fun onCreate() { super.onCreate(); instance = this; CompanionService.instance?.features?.publish() }
 override fun onDestroy() { instance = null; CompanionService.instance?.features?.publish(); super.onDestroy() }
 fun commit(text: String): Boolean = currentInputConnection?.commitText(text, 1) ?: false
}
class UnityAccessibility : AccessibilityService() {
 companion object { @Volatile var instance: UnityAccessibility? = null }
 override fun onServiceConnected() { instance = this; CompanionService.instance?.features?.publish() }
 override fun onDestroy() { instance = null; CompanionService.instance?.features?.publish(); super.onDestroy() }
 override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
 override fun onInterrupt() { }
 fun gesture(data: JSONObject, swipe: Boolean, completed: (Boolean) -> Unit) {
  val metrics = resources.displayMetrics
  fun coordinate(key: String): Float { val n = data.getDouble(key); require(n.isFinite() && n in 0.0..1.0); return n.toFloat() }
  val path = Path().apply { moveTo(coordinate("x") * metrics.widthPixels, coordinate("y") * metrics.heightPixels)
   if (swipe) lineTo(coordinate("end_x") * metrics.widthPixels, coordinate("end_y") * metrics.heightPixels) }
  val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, if (swipe) 300 else 60)).build()
  val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
   override fun onCompleted(gesture: GestureDescription?) { completed(true) }
   override fun onCancelled(gesture: GestureDescription?) { completed(false) }
  }, null)
  if (!accepted) completed(false)
 }
}
class RemoteInputFeature(service: CompanionService) : Module(service, "remote_input") {
 override fun capability() = status(UnityKeyboard.instance != null || UnityAccessibility.instance != null, "Select Unity keyboard for typing or explicitly enable Unity accessibility for touch")
 override fun command(action: String, data: JSONObject): JSONObject {
  if (action == "text") return onMain {
   val text = data.getString("text"); require(text.toByteArray().size <= 4096)
   check(UnityKeyboard.instance?.commit(text) == true) { "Unity keyboard must be selected and a text field focused" }
   JSONObject().put("state", "typed")
  }
  require(action == "tap" || action == "swipe")
  val future = CompletableFuture<Boolean>()
  Handler(Looper.getMainLooper()).post {
   try { (UnityAccessibility.instance ?: error("Enable Unity accessibility on phone")).gesture(data, action == "swipe") { future.complete(it) } }
   catch (e: Exception) { future.completeExceptionally(e) }
  }
  check(future.get(5, TimeUnit.SECONDS)) { "Phone gesture cancelled" }
  return JSONObject().put("state", "performed")
 }
}
