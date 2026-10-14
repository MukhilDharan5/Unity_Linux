package org.unityconnect.companion

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.webkit.MimeTypeMap
import android.widget.ScrollView
import android.widget.Toast
import java.util.UUID

/** Each tap contains its own immutable item, so later receipts cannot change what opens. */
internal object ReceivedItems {
 const val CHANNEL = "unity.received"
 const val KIND = "received_kind"
 const val VALUE = "received_value"
 const val NAME = "received_name"
 const val MIME = "received_mime"
 private const val COPY = "copy_text"
 fun channel(context: Context) {
  context.getSystemService(NotificationManager::class.java).createNotificationChannel(
   NotificationChannel(CHANNEL, "Received files, links and text", NotificationManager.IMPORTANCE_HIGH).apply {
    description = "Open content shared from your computer"; lockscreenVisibility = Notification.VISIBILITY_PRIVATE
   })
 }
 fun mime(name: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) ?: "application/octet-stream"
 fun file(context: Context, uri: Uri, name: String) {
  require(uri.scheme == "content")
  notify(context, "file", uri.toString(), name, mime(name), "File received", name, "Open file")
 }
 fun link(context: Context, url: String) {
  validateUrl(url)
  notify(context, "link", url, "Web page", "", "Link received", url, "Open link")
 }
 fun text(context: Context, text: String) {
  require(text.toByteArray().size <= 64 * 1024 - 256)
  notify(context, "text", text, "Shared text", "text/plain", "Text received", text.take(240), "Open text")
 }
 fun validateUrl(value: String): Uri {
  val uri = Uri.parse(value)
  require(value.length <= 8192 && value.none { it.code < 32 } && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "Invalid received web address" }
  return uri
 }
 private fun notify(context: Context, kind: String, value: String, name: String, mime: String, title: String, body: String, action: String) {
  channel(context)
  val item = UUID.randomUUID().toString()
  val intent = Intent(context, ReceivedItemActivity::class.java).setData(Uri.parse("unity-received://item/$item"))
   .putExtra(KIND, kind).putExtra(VALUE, value).putExtra(NAME, name).putExtra(MIME, mime)
   .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
  val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_unity_symbolic)
   .setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
   .setContentIntent(pending).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE)
   .setCategory(Notification.CATEGORY_EVENT)
   .addAction(Notification.Action.Builder(null, action, pending).build())
  if (kind == "text") {
   val copy = PendingIntent.getActivity(context, 0, Intent(intent).setAction(COPY), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
   notification.addAction(Notification.Action.Builder(null, "Copy text", copy).build())
  }
  // A tag gives every receipt a distinct identity, including repeated shares of the same URL.
  runCatching { context.getSystemService(NotificationManager::class.java).notify(item, 2, notification.build()) }
 }
 internal fun viewerIntent(intent: Intent): Intent {
  val value = intent.getStringExtra(VALUE) ?: error("Received item is missing")
  return when (intent.getStringExtra(KIND)) {
   "link" -> Intent(Intent.ACTION_VIEW, validateUrl(value)).addCategory(Intent.CATEGORY_BROWSABLE)
   "file" -> {
    val uri = Uri.parse(value)
    require(uri.scheme == "content" && uri.authority == "media") { "Invalid received file" }
    Intent(Intent.ACTION_VIEW).setDataAndType(uri, intent.getStringExtra(MIME) ?: "application/octet-stream")
     .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Received file", uri) }
   }
   else -> error("Unsupported received item")
  }
 }
 internal fun copying(intent: Intent) = intent.action == COPY
}

/** An activity keeps notification taps compatible with Android's background launch restrictions. */
class ReceivedItemActivity : Activity() {
 private lateinit var value: String
 override fun attachBaseContext(base: Context) {
  val config = Configuration(base.resources.configuration)
  when (base.getSharedPreferences("settings", MODE_PRIVATE).getString("appearance", "system")) {
   "light" -> config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
   "dark" -> config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
  }
  super.attachBaseContext(base.createConfigurationContext(config))
 }
 override fun onCreate(saved: Bundle?) {
  super.onCreate(saved)
  value = intent.getStringExtra(ReceivedItems.VALUE).orEmpty()
  if (intent.getStringExtra(ReceivedItems.KIND) == "text") {
   if (ReceivedItems.copying(intent)) {
    copyText(); finish(); return
   }
   showText(); return
  }
  try { startActivity(ReceivedItems.viewerIntent(intent)); finish() }
  catch (_: ActivityNotFoundException) { showFallback() }
  catch (e: Exception) { Toast.makeText(this, e.message ?: "Received item unavailable", Toast.LENGTH_LONG).show(); finish() }
 }
 private fun copyText() {
  getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Unity", value))
  Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show()
 }
 private fun showText() {
  val ui = AdwaitaUi(this)
  val content = ui.column().apply { setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24)) }
  content.addView(ui.label("Text received", 24f, true))
  content.addView(ui.label(value).apply { setTextIsSelectable(true); setPadding(0, ui.dp(20), 0, ui.dp(20)) })
  content.addView(ui.button("Copy text", true) { copyText() })
  showContent(content)
 }
 private fun showFallback() {
  val ui = AdwaitaUi(this)
  val file = intent.getStringExtra(ReceivedItems.KIND) == "file"
  val content = ui.column().apply { setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24)) }
  content.addView(ui.label(intent.getStringExtra(ReceivedItems.NAME) ?: "Received file", 24f, true))
  content.addView(ui.label(if (file) "No installed app can open this file type. The file is saved in Downloads/Unity." else "No installed browser can open this link. $value", secondary = true))
  content.addView(ui.button("Choose an app", true) {
   runCatching {
    val view = ReceivedItems.viewerIntent(intent)
    if (file) view.setDataAndType(Uri.parse(value), "*/*")
    startActivity(Intent.createChooser(view, if (file) "Open received file" else "Open received link"))
   }.onFailure { Toast.makeText(this, if (file) "Install an app that supports this file type" else "Install a browser to open this link", Toast.LENGTH_LONG).show() }
  })
  showContent(content)
 }
 @Suppress("DEPRECATION")
 private fun showContent(content: View) {
  if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
  else @Suppress("DEPRECATION") run { window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION }
  val scroll = ScrollView(this).apply { addView(content) }
  setContentView(scroll)
  scroll.setOnApplyWindowInsetsListener { view, insets ->
   if (Build.VERSION.SDK_INT >= 30) {
    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
   } else @Suppress("DEPRECATION") run { view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom) }
   insets
  }
  scroll.requestApplyInsets()
 }
}
