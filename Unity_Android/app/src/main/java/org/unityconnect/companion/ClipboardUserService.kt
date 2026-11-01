package org.unityconnect.companion

import android.content.ClipData
import android.content.Context
import android.os.*
import java.lang.reflect.Method

/** Runs only in Shizuku's authorized shell/root process; exposes clipboard reads, no shell commands. */
class ClipboardUserService(context: Context) : IClipboardAccess.Stub() {
 private val owner = context.applicationInfo.uid
 private val user = owner / 100000
 private var api: PrivilegedClipboardApi? = null
 private var receiver: IClipboardListener? = null
 private var death: IBinder.DeathRecipient? = null
 private val thread = HandlerThread("UnityClipboard").apply { start() }
 private val handler = Handler(thread.looper)
 private val sample = Runnable { read() }
 private val notification = object : Binder() {
  override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
   if (code == INTERFACE_TRANSACTION) { reply?.writeString("android.content.IOnPrimaryClipChangedListener"); return true }
   if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
   data.enforceInterface("android.content.IOnPrimaryClipChangedListener")
   handler.removeCallbacks(sample); handler.post(sample)
   return true
  }
 }
 private fun requireOwner() { check(Binder.getCallingUid() == owner) { "Clipboard client identity mismatch" } }
 @Synchronized override fun start(listener: IClipboardListener): String {
  requireOwner(); stopInternal()
  return try {
   val target = PrivilegedClipboardApi.connect(user, notification)
   val died = IBinder.DeathRecipient { synchronized(this) { stopInternal() } }
   listener.asBinder().linkToDeath(died, 0)
   receiver = listener; death = died; api = target
   target.register()
   // Verify the read call as well as listener registration before advertising availability.
   target.read()
   handler.post(sample)
   ""
  } catch (_: Exception) {
   stopInternal()
   "This Android version or device policy denied Shizuku clipboard access."
  }
 }
 override fun refresh() { requireOwner(); handler.removeCallbacks(sample); handler.post(sample) }
 @Synchronized override fun stop() { requireOwner(); stopInternal() }
 @Synchronized private fun stopInternal() {
  handler.removeCallbacks(sample)
  api?.let { runCatching { it.unregister() } }; api = null
  death?.let { callback -> receiver?.let { runCatching { it.asBinder().unlinkToDeath(callback, 0) } } }
  receiver = null; death = null
 }
 @Synchronized private fun read() {
  val target = api ?: return
  try {
   val observed = SystemClock.elapsedRealtimeNanos()
   val clip = target.read() ?: return // Locked/empty clipboard: do not clear the peer.
   if (clip.itemCount != 1) return
   val text = clip.getItemAt(0).text?.toString() ?: return
   if (text.toByteArray(Charsets.UTF_8).size <= 65280 && '\u0000' !in text) receiver?.onText(text, observed)
  } catch (_: Exception) {
   runCatching { receiver?.onError("Background clipboard access stopped. Review Shizuku access.") }
   stopInternal()
  }
 }
 override fun destroy() {
  val caller = Binder.getCallingUid()
  check(caller == owner || caller == 0 || caller == 2000)
  synchronized(this) { stopInternal() }
  thread.quitSafely()
  kotlin.system.exitProcess(0)
 }
}

/** Select known AOSP signatures by type, avoiding hard-coded Binder transaction numbers. */
internal class PrivilegedClipboardApi(private val service: Any, private val user: Int, private val listener: Any) {
 private fun invoke(name: String, withListener: Boolean = false): Any? {
  val methods = service.javaClass.methods.filter { it.name == name }
  val method = methods.singleOrNull { supported(it, withListener) } ?: error("Unsupported clipboard API")
  method.isAccessible = true
  var strings = 0; var integers = 0
  val arguments = method.parameterTypes.map { type -> when {
   type == String::class.java -> if (strings++ == 0) "com.android.shell" else null
   type == Int::class.javaPrimitiveType -> if (integers++ == 0) user else 0 // default virtual device
   type.name == "android.content.IOnPrimaryClipChangedListener" -> listener
   else -> error("Unsupported clipboard parameter")
  } }.toTypedArray()
  val identity = Binder.clearCallingIdentity()
  try { return method.invoke(service, *arguments) }
  finally { Binder.restoreCallingIdentity(identity) }
 }
 fun register() { invoke("addPrimaryClipChangedListener", true) }
 fun unregister() { invoke("removePrimaryClipChangedListener", true) }
 fun read(): ClipData? = invoke("getPrimaryClip") as? ClipData
 companion object {
  internal fun supported(method: Method, withListener: Boolean): Boolean {
   val types = method.parameterTypes
   return types.count { it == String::class.java } in 1..2 &&
    types.count { it == Int::class.javaPrimitiveType } in 1..2 &&
    types.count { it.name == "android.content.IOnPrimaryClipChangedListener" } == (if (withListener) 1 else 0) &&
    types.all { it == String::class.java || it == Int::class.javaPrimitiveType || it.name == "android.content.IOnPrimaryClipChangedListener" }
  }
  fun connect(user: Int, notification: IBinder): PrivilegedClipboardApi {
   val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
   val service = Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
   val listener = Class.forName("android.content.IOnPrimaryClipChangedListener\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, notification)
   return PrivilegedClipboardApi(service, user, listener)
  }
 }
}
