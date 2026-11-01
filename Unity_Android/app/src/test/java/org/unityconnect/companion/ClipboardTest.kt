package org.unityconnect.companion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardTest {
 private class FakeBackground : BackgroundClipboard {
  override var available = false
  override val reason = "Test Shizuku reader"
  var watching = false
  var refreshes = 0
  var closed = false
  override fun watch(enabled: Boolean) { watching = enabled }
  override fun refresh() { refreshes++ }
  override fun request() { }
  override fun close() { closed = true; watching = false }
 }
 private fun idle() = shadowOf(Looper.getMainLooper()).idle()
 private fun await(test: () -> Boolean) {
  val end = System.nanoTime() + 5_000_000_000
  while (!test()) { check(System.nanoTime() < end) { "Timed out" }; idle(); Thread.sleep(10) }
 }
 private fun isClipboard(value: JSONObject) = value.optString("type") == "event" && value.optString("feature") == "clipboard"
 private fun drain(link: TestConnection): List<JSONObject> {
  idle(); Thread.sleep(120)
  val values = mutableListOf<JSONObject>(); link.lan.messages.drainTo(values); link.bluetooth.messages.drainTo(values)
  return values.filter(::isClipboard)
 }
 @Test fun changesSendImmediatelyWithoutButtonsAndRemoteWritesNeverEcho() {
  val controller = Robolectric.buildService(CompanionService::class.java).create(); val service = controller.get()
  val manager = service.getSystemService(ClipboardManager::class.java)
  try { TestConnection(service).use { link ->
   idle(); drain(link)
   val text = "தமிழ் 😀\n$(literal) `text`\n"
   manager.setPrimaryClip(ClipData.newPlainText("Other app", text)); idle()
   val event = link.lan.next(::isClipboard)
   assertEquals(text, event.getJSONObject("data").getString("text"))
   assertTrue(event.getJSONObject("data").getBoolean("automatic"))
   val command = JSONObject().put("type", "command").put("id", "ab".repeat(16)).put("feature", "clipboard").put("action", "set").put("data", JSONObject().put("text", "from computer"))
   link.lan.send(command)
   await { manager.primaryClip?.getItemAt(0)?.text == "from computer" }
   val result = link.lan.next { it.optString("type") == "result" && it.optString("id") == command.getString("id") }
   assertEquals("applied", result.getJSONObject("data").getString("state"))
   assertFalse(service.getSharedPreferences("pending", 0).contains("clipboard"))
   assertTrue(drain(link).isEmpty())
   // Re-copying an earlier local value after receiving a different value is a new change.
   manager.setPrimaryClip(ClipData.newPlainText("Other app", text)); idle()
   assertEquals(text, link.lan.next(::isClipboard).getJSONObject("data").getString("text"))
   service.features.clipboard.publish(); assertTrue(drain(link).isEmpty())
  } } finally { controller.destroy(); idle() }
 }
 @Test fun disabledOversizedAndNonTextClipboardsDoNotTransmit() {
  val controller = Robolectric.buildService(CompanionService::class.java).create(); val service = controller.get()
  val manager = service.getSystemService(ClipboardManager::class.java)
  try { TestConnection(service).use { link ->
   idle(); drain(link)
   val prefs = service.getSharedPreferences("settings", 0)
   prefs.edit().putBoolean("enable.clipboard", false).commit()
   manager.setPrimaryClip(ClipData.newPlainText("Other app", "private")); idle()
   assertTrue(drain(link).isEmpty())
   assertThrows(IllegalStateException::class.java) { service.features.clipboard.command("set", JSONObject().put("text", "blocked")) }
   assertEquals("private", manager.primaryClip!!.getItemAt(0).text)
   prefs.edit().putBoolean("enable.clipboard", true).commit()
   for (text in listOf("😀".repeat(17_000), "null\u0000byte")) {
    manager.setPrimaryClip(ClipData.newPlainText("Other app", text)); idle()
    assertTrue(drain(link).isEmpty())
    assertThrows(IllegalArgumentException::class.java) { service.features.clipboard.command("set", JSONObject().put("text", text)) }
   }
   manager.setPrimaryClip(ClipData.newRawUri("File", Uri.parse("content://example/private"))); idle()
   assertTrue(drain(link).isEmpty())
   manager.setPrimaryClip(ClipData.newPlainText("Empty text", "")); idle()
   assertEquals("", link.lan.next(::isClipboard).getJSONObject("data").getString("text"))
  } } finally { controller.destroy(); idle() }
 }
 @Test fun reconnectPublishesCurrentLocalTextAndReportsBackgroundRequirementAccurately() {
  val controller = Robolectric.buildService(CompanionService::class.java).create(); val service = controller.get()
  try {
   assertEquals("available", service.features.clipboard.capability().getString("status"))
   assertFalse(service.features.clipboard.backgroundAccess())
   Settings.Secure.putString(service.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, ComponentName(service, UnityKeyboard::class.java).flattenToString())
   assertTrue(service.features.clipboard.backgroundAccess())
   assertTrue(service.features.clipboard.capability().getString("reason").contains("including in the background"))
   TestConnection(service).use { first -> idle(); drain(first) }
   service.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Offline", "latest offline text")); idle()
   TestConnection(service).use { second ->
    idle()
    assertEquals("latest offline text", second.lan.next(::isClipboard).getJSONObject("data").getString("text"))
   }
  } finally { controller.destroy(); idle() }
 }
 @Test fun privilegedEventsSyncWithoutChangingTheKeyboardAndDiscardStaleCallbacks() {
  val controller = Robolectric.buildService(CompanionService::class.java).create(); val service = controller.get()
  val feature = service.features.clipboard
  feature.background.close()
  val background = FakeBackground(); feature.background = background
  val keyboard = "com.example.keyboard/.Keyboard"
  Settings.Secure.putString(service.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, keyboard)
  try { TestConnection(service).use { link ->
   idle(); drain(link)
   background.available = true; feature.watch(); idle()
   assertTrue(background.watching); assertTrue(feature.backgroundAccess()); assertTrue(background.refreshes > 0)
   feature.backgroundText("Copied in another app தமிழ்", SystemClock.elapsedRealtimeNanos())
   assertEquals("Copied in another app தமிழ்", link.lan.next(::isClipboard).getJSONObject("data").getString("text"))
   val stale = SystemClock.elapsedRealtimeNanos()
   shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10))
   feature.command("set", JSONObject().put("text", "new computer text"))
   feature.backgroundText("old callback", stale)
   feature.backgroundText("new computer text", SystemClock.elapsedRealtimeNanos())
   assertTrue(drain(link).isEmpty())
   feature.backgroundText("new phone text", SystemClock.elapsedRealtimeNanos())
   assertEquals("new phone text", link.lan.next(::isClipboard).getJSONObject("data").getString("text"))
   service.getSharedPreferences("settings", 0).edit().putBoolean("enable.clipboard", false).commit()
   feature.watch(); idle(); assertFalse(background.watching)
   feature.backgroundText("disabled callback", SystemClock.elapsedRealtimeNanos()); assertTrue(drain(link).isEmpty())
   background.available = false; assertFalse(feature.backgroundAccess())
   assertEquals(keyboard, Settings.Secure.getString(service.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
  } } finally { controller.destroy(); idle(); assertTrue(background.closed) }
 }
 class ModernClipboard {
  var arguments: List<Any?> = emptyList()
  fun getPrimaryClip(pkg: String, attribution: String?, user: Int, device: Int): ClipData {
   arguments = listOf(pkg, attribution, user, device)
   return ClipData.newPlainText("Test", "background text")
  }
 }
 class LegacyClipboard {
  var arguments: List<Any?> = emptyList()
  fun getPrimaryClip(pkg: String, user: Int): ClipData {
   arguments = listOf(pkg, user); return ClipData.newPlainText("Test", "legacy text")
  }
 }
 class UnknownClipboard { fun getPrimaryClip(pkg: String, user: Int, unsupported: Boolean): ClipData? = null }
 @Test fun privilegedReaderUsesShellAttributionAndTheCorrectAndroidUser() {
  val modern = ModernClipboard()
  assertEquals("background text", PrivilegedClipboardApi(modern, 10, Any()).read()!!.getItemAt(0).text)
  assertEquals(listOf("com.android.shell", null, 10, 0), modern.arguments)
  val legacy = LegacyClipboard()
  assertEquals("legacy text", PrivilegedClipboardApi(legacy, 0, Any()).read()!!.getItemAt(0).text)
  assertEquals(listOf("com.android.shell", 0), legacy.arguments)
  assertThrows(IllegalStateException::class.java) { PrivilegedClipboardApi(UnknownClipboard(), 0, Any()).read() }
 }
}
