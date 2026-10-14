package org.unityconnect.companion

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CompanionService : Service() {
 companion object {
  @Volatile var instance: CompanionService? = null
 }
 @Volatile var uiState = ConnectionState.disconnected
  private set
 @Volatile var transferState = TransferState()
  private set
 private val transferActive = AtomicBoolean(false)
 private val transfers = ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(1))
 lateinit var credentials: Credentials
 lateinit var connection: Connection
 lateinit var features: FeatureRegistry
 private val commands = ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(16))
 private val receiver = object : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
   if (::features.isInitialized && ::connection.isInitialized && connection.ready) {
    when (intent.action) {
     Intent.ACTION_BATTERY_CHANGED -> features.battery.publish(intent)
     "android.intent.action.PHONE_STATE" -> features.calls.publish(intent)
    }
   }
  }
 }
 private val networkCallback = object : ConnectivityManager.NetworkCallback() {
  override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
   if (::features.isInitialized && ::connection.isInitialized && connection.ready) features.network.publish()
  }
  override fun onLost(network: Network) { if (::features.isInitialized) features.network.publish() }
 }
 override fun onCreate() {
  super.onCreate(); instance = this
  val nm = getSystemService(NotificationManager::class.java)
  nm.createNotificationChannel(NotificationChannel("unity.connection", "Device connection", NotificationManager.IMPORTANCE_LOW))
  nm.createNotificationChannel(NotificationChannel("unity.actions", "Phone actions", NotificationManager.IMPORTANCE_DEFAULT))
  ReceivedItems.channel(this)
  credentials = Credentials(this); features = FeatureRegistry(this); connection = Connection(this)
  val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { addAction("android.intent.action.PHONE_STATE") }
  if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_EXPORTED) else registerReceiver(receiver, filter)
  getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
 }
 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
  startForeground(1, notification(uiState.title), foregroundTypes())
  if (intent?.action != "refresh") status("Connecting to your saved computer…", ConnectionPhase.CONNECTING)
  if (intent?.action == "refresh") features.publish() else if (intent != null) { connection.stop(); connection = Connection(this); connection.start() }
  // START_STICKY restores saved settings after process termination, subject to Android OEM limits.
  if (intent == null) { connection.start() }
  return START_STICKY
 }
 private fun notification(text: String): Notification {
  val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
  return Notification.Builder(this, "unity.connection").setSmallIcon(R.drawable.ic_unity_symbolic)
   .setContentTitle("Unity").setContentText(text).setContentIntent(open).setOngoing(true).build()
 }
 fun uiChanged() { CompanionEvents.changed() }
 private fun foregroundTypes() = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
  (if (features.audio.active()) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0) or
  (if (features.screen.projectionForeground) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
 fun refreshForeground() {
  val update = Runnable { if (instance === this) startForeground(1, notification(when {
   features.screen.projectionForeground -> "Sharing phone screen"
   features.audio.active() -> "Playing laptop audio"
   else -> uiState.title
  }), foregroundTypes()) }
  if (Looper.myLooper() == Looper.getMainLooper()) update.run() else Handler(Looper.getMainLooper()).post(update)
 }
 fun audioPlaybackChanged() { refreshForeground() }
 fun sendFile(uri: Uri) {
  check(connection.ready) { "Connect first" }
  check(features.files.capability().getString("status") == "available") { "Enable file transfers in Features" }
  check(transferActive.compareAndSet(false, true)) { "A file is already being sent" }
  transferState = TransferState(true, "Sending file…"); uiChanged()
  try {
   transfers.execute {
    try { features.files.send(uri); transferState = TransferState(title = "File sent") }
    catch (e: Exception) { transferState = TransferState(title = (e.message ?: "Transfer failed").take(180), failed = true) }
    finally { transferActive.set(false); uiChanged() }
   }
  } catch (e: Exception) {
   transferActive.set(false); transferState = TransferState(title = "Transfer unavailable", failed = true); uiChanged(); throw e
  }
 }
 fun status(text: String, phase: ConnectionPhase) {
  uiState = ConnectionState(phase, text)
  getSystemService(NotificationManager::class.java).notify(1, notification(text))
  uiChanged()
 }
 fun event(feature: String, data: JSONObject, blocking: Boolean = false) {
  check(connection.send(JSONObject().put("type", "event").put("feature", feature).put("data", data), blocking)) { "Connection unavailable or send queue full" }
 }
 fun result(id: String, ok: Boolean, data: JSONObject = JSONObject(), error: String = "") {
  connection.send(JSONObject().put("type", "result").put("id", id).put("ok", ok).put("data", data).put("error", error.take(512)), true)
 }
 fun dispatch(message: JSONObject) {
  val id = message.optString("id")
  if (!Protocol.validId(id)) { connection.stop(); return }
  val run = Runnable {
   try {
    val feature = message.getString("feature"); val action = message.getString("action"); val data = message.getJSONObject("data")
    val module = features.modules[feature] ?: error("Unsupported feature")
    val cap = module.capability()
    check((feature in listOf("audio", "screen") && action == "stop") || cap.getString("status") in listOf("available", "user_action_required")) { cap.getString("reason") }
    if (feature in listOf("audio", "screen") && action == "start") require(data.getString("request_id") == id)
    val response = module.command(action, data)
    if (response != null) result(id, true, response)
   } catch (e: Exception) { result(id, false, error = e.message ?: "Phone operation failed") }
  }
  // File chunks must remain strictly ordered; their bounded writes are handled by the reader.
  if (message.optString("feature") == "files") run.run()
  else try { commands.execute(run) } catch (_: Exception) { result(id, false, error = "Phone command queue full") }
 }
 fun prompt(title: String, action: String) {
  val intent = Intent(this, MainActivity::class.java).putExtra("phone_action", action)
  val pending = PendingIntent.getActivity(this, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  getSystemService(NotificationManager::class.java).notify(action.hashCode(), Notification.Builder(this, "unity.actions")
   .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText("Tap to review on your phone")
   .setContentIntent(pending).setAutoCancel(true).build())
 }
 override fun onBind(intent: Intent?) = null
 override fun onDestroy() {
  instance = null; connection.stop(); features.disconnected(); features.media.close(); features.sms.close(); commands.shutdownNow(); transfers.shutdownNow()
  uiState = ConnectionState.disconnected; uiChanged()
  unregisterReceiver(receiver); getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
  super.onDestroy()
 }
}
