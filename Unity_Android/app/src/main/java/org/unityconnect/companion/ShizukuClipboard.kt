package org.unityconnect.companion

import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

internal interface BackgroundClipboard {
 val available: Boolean
 val reason: String
 fun watch(enabled: Boolean)
 fun refresh()
 fun request()
 fun close()
}

/** The app remains unprivileged. Only the clipboard reader runs in Shizuku's user service. */
internal class ShizukuClipboard(private val context: Context, private val text: (String, Long) -> Unit, private val changed: () -> Unit) : BackgroundClipboard {
 private val main = Handler(Looper.getMainLooper())
 private val worker = Executors.newSingleThreadExecutor()
 private val args = Shizuku.UserServiceArgs(ComponentName(context, ClipboardUserService::class.java))
  .daemon(false).processNameSuffix("clipboard").version(34)
 private var wanted = false
 private var closed = false
 private var listening = false
 private var epoch = 0
 private var binding: ServiceConnection? = null
 private var remote: IClipboardAccess? = null
 private var boundAt = 0L
 override var available = false
  private set
 override var reason = "Allow Shizuku access to send clipboard text while using other apps. Your keyboard stays the same."
  private set
 private val received = Shizuku.OnBinderReceivedListener { update() }
 private val died = Shizuku.OnBinderDeadListener { disconnect(); update() }
 private val permission = Shizuku.OnRequestPermissionResultListener { code, _ -> if (code == REQUEST) update() }
 private val health = object : Runnable {
  override fun run() { if (closed || !wanted) return; update(); main.postDelayed(this, 5000) }
 }
 private fun state(ready: Boolean, message: String) {
  if (available == ready && reason == message) return
  available = ready; reason = message; changed()
 }
 private fun authorized() = runCatching {
  Shizuku.pingBinder() && Shizuku.getVersion() >= 13 && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
 }.getOrDefault(false)
 override fun watch(enabled: Boolean) {
  if (closed) return
  wanted = enabled
  if (!listening) {
   listening = true
   Shizuku.addBinderReceivedListener(received)
   Shizuku.addBinderDeadListener(died)
   Shizuku.addRequestPermissionResultListener(permission)
  }
  main.removeCallbacks(health); update()
  if (wanted) main.postDelayed(health, 5000)
 }
 private fun update() {
  if (closed) return
  if (!wanted) { disconnect(); return }
  if (!authorized()) {
   disconnect()
   state(false, if (Shizuku.pingBinder()) "Allow Unity in Shizuku to enable background clipboard sending." else "Start Shizuku to enable background clipboard sending. Your keyboard stays the same.")
   return
  }
  if (binding != null) {
   if (remote?.asBinder()?.pingBinder() == false || !available && SystemClock.elapsedRealtime() - boundAt > 10000) disconnect()
   else return
  }
  val generation = ++epoch
  val connection = object : ServiceConnection {
   override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
    if (closed || !wanted || generation != epoch || binder == null) return
    val access = IClipboardAccess.Stub.asInterface(binder)
    remote = access
    val listener = object : IClipboardListener.Stub() {
     override fun onText(value: String, observedAtNanos: Long) { main.post { if (!closed && wanted && generation == epoch && authorized()) text(value, observedAtNanos) } }
     override fun onError(message: String) { main.post {
      if (generation == epoch) { disconnect(); state(false, message.take(180)) }
     } }
    }
    worker.execute {
     val error = runCatching { access.start(listener) }.getOrElse { "Could not start the Shizuku clipboard reader." }
     main.post {
      if (closed || !wanted || generation != epoch) return@post
      if (error.isEmpty()) state(true, "Automatic background clipboard sync is active through Shizuku. Your keyboard stays the same.")
      else { disconnect(); state(false, error.take(180)) }
     }
    }
   }
   override fun onServiceDisconnected(name: ComponentName?) {
    if (generation == epoch) { disconnect(); state(false, "Clipboard reader stopped. Reconnecting through Shizuku…") }
   }
  }
  binding = connection
  boundAt = SystemClock.elapsedRealtime()
  state(false, "Starting background clipboard access through Shizuku…")
  runCatching { Shizuku.bindUserService(args, connection) }.onFailure {
   disconnect(); state(false, "Could not bind the clipboard reader. Start Shizuku and review Unity access.")
  }
 }
 override fun refresh() {
  val access = remote ?: return
  if (!available || closed) return
  val generation = epoch
  worker.execute { runCatching { access.refresh() }.onFailure { main.post { if (generation == epoch) disconnect() } } }
 }
 private fun disconnect() {
  ++epoch
  remote = null
  val connection = binding; binding = null
  if (connection != null) runCatching { Shizuku.unbindUserService(args, connection, true) }
  state(false, "Background clipboard reader is stopped.")
 }
 override fun request() {
  check(!closed)
  if (!Shizuku.pingBinder()) {
   val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
   context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
   return
  }
  check(Shizuku.getVersion() >= 13) { "Update Shizuku to version 13 or newer" }
  if (authorized()) update()
  else if (Shizuku.shouldShowRequestPermissionRationale()) {
   val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") ?: error("Open Shizuku and allow Unity under Authorized applications")
   context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  } else Shizuku.requestPermission(REQUEST)
 }
 override fun close() {
  closed = true; wanted = false; main.removeCallbacks(health); disconnect()
  if (listening) {
   Shizuku.removeBinderReceivedListener(received); Shizuku.removeBinderDeadListener(died)
   Shizuku.removeRequestPermissionResultListener(permission); listening = false
  }
  worker.shutdown()
 }
 private companion object { const val REQUEST = 7731 }
}
