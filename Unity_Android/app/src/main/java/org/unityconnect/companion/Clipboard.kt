package org.unityconnect.companion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.security.MessageDigest

/** Service-owned, event-driven text sync. Android still controls background read access. */
class ClipboardFeature(service: CompanionService) : Module(service, "clipboard") {
 private val main = Handler(Looper.getMainLooper())
 private val manager = service.getSystemService(ClipboardManager::class.java)
 private val memory = service.getSharedPreferences("clipboard_sync", 0)
 private var watching = false
 private var sent: String? = null
 private var remote: String? = memory.getString("remote", null)
 private val changed = ClipboardManager.OnPrimaryClipChangedListener { publish() }
 private var queued: String? = null
 private var appliedAt = 0L
 private val retry = Runnable { queued?.let { publishText(it) } }
 internal var background: BackgroundClipboard = ShizukuClipboard(service, ::backgroundText, service::uiChanged)

 fun backgroundAccess(): Boolean = background.available || ComponentName.unflattenFromString(
  Settings.Secure.getString(service.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
 )?.packageName == service.packageName || granted("android.permission.READ_CLIPBOARD_IN_BACKGROUND")

 override fun capability() = status(reason = if (backgroundAccess())
  "Automatic text sync in both directions, including in the background"
 else "Received text is applied automatically. ${background.reason}")

 fun backgroundStatus() = background.reason
 fun requestBackgroundAccess() { watch(); background.request() }
 internal fun backgroundText(text: String, observedAtNanos: Long) {
  if (observedAtNanos < appliedAt) return
  publishText(text)
 }

 fun watch() { main.post {
  if (!watching) { manager.addPrimaryClipChangedListener(changed); watching = true }
  background.watch(enabled() && service.connection.ready)
  publishNow()
 } }
 fun publish() { if (Looper.myLooper() == Looper.getMainLooper()) publishNow() else main.post { publishNow() } }
 private fun publishNow() {
  main.removeCallbacks(retry)
  if (!enabled() || !service.connection.ready) return
  if (background.available) { background.refresh(); return }
  // A denied background read returns null. Do not replace or clear the other device's clipboard.
  val clip = runCatching { manager.primaryClip }.getOrNull() ?: return
  if (clip.itemCount != 1) return
  val text = clip.getItemAt(0).text?.toString() ?: return
  publishText(text)
 }
 private fun publishText(text: String) {
  if (!enabled() || !service.connection.ready) return
  main.removeCallbacks(retry); queued = null
  if (text.toByteArray(Charsets.UTF_8).size > 64 * 1024 - 256 || '\u0000' in text) return
  val hash = digest(text)
  if (hash == sent || hash == remote) return
  runCatching { service.event(id, JSONObject().put("text", text).put("automatic", true)) }
   .onSuccess { sent = hash; remote = null; memory.edit().remove("remote").apply() }
   .onFailure { queued = text; main.postDelayed(retry, 100) }
 }
 override fun command(action: String, data: JSONObject): JSONObject = onMain {
  check(enabled()) { "Enable clipboard sharing" }
  require(action == "set")
  val text = data.getString("text")
  require(text.toByteArray(Charsets.UTF_8).size <= 64 * 1024 - 256 && '\u0000' !in text)
  val previous = remote
  // Record before writing: some implementations invoke the listener synchronously.
  remote = digest(text)
  try { manager.setPrimaryClip(ClipData.newPlainText("Unity", text)) }
  catch (error: Exception) { remote = previous; throw error }
  appliedAt = SystemClock.elapsedRealtimeNanos()
  queued = null; main.removeCallbacks(retry)
  sent = remote
  memory.edit().putString("remote", remote).apply()
  service.getSharedPreferences("pending", 0).edit().remove("clipboard").apply()
  service.uiChanged()
  JSONObject().put("state", "applied")
 }
 fun disconnected() { main.post { sent = null; queued = null; main.removeCallbacks(retry); background.watch(false) } }
 fun close() { main.post {
  main.removeCallbacks(retry)
  if (watching) manager.removePrimaryClipChangedListener(changed)
  watching = false
  queued = null; background.close()
 } }
 private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
