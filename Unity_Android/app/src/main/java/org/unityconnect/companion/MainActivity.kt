package org.unityconnect.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONObject

/** Companion UI. Connections and feature execution remain in the service. */
class MainActivity : Activity() {
 private enum class Page(val title: String, val icon: Int, val navId: Int, val pageId: Int) {
  OVERVIEW("Overview", R.drawable.ic_overview, R.id.nav_overview, R.id.page_overview),
  SHARING("Sharing", R.drawable.ic_sharing, R.id.nav_sharing, R.id.page_sharing),
  FEATURES("Features", R.drawable.ic_features, R.id.nav_features, R.id.page_features),
  SETTINGS("Settings", R.drawable.ic_settings, R.id.nav_settings, R.id.page_settings)
 }
 private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
 private val pending by lazy { getSharedPreferences("pending", MODE_PRIVATE) }
 private lateinit var ui: AdwaitaUi
 private lateinit var host: EditText
 private lateinit var port: EditText
 private lateinit var fingerprint: EditText
 private lateinit var url: EditText
 private lateinit var excluded: EditText
 private lateinit var content: FrameLayout
 private lateinit var headerTitle: TextView
 private lateinit var connectionTitle: TextView
 private lateinit var connectionDetail: TextView
 private lateinit var connectionBadge: TextView
 private lateinit var computerRow: AdwaitaUi.Row
 private lateinit var connectButton: Button
 private lateinit var disconnectButton: Button
 private lateinit var receivedClipboard: AdwaitaUi.Row
 private lateinit var receivedUrl: AdwaitaUi.Row
 private lateinit var folderRow: AdwaitaUi.Row
 private lateinit var notificationPermission: AdwaitaUi.Row
 private lateinit var smsPermission: AdwaitaUi.Row
 private lateinit var photoPermission: AdwaitaUi.Row
 private lateinit var dndPermission: AdwaitaUi.Row
 private lateinit var transferRow: AdwaitaUi.Row
 private val pages = linkedMapOf<Page, ScrollView>()
 private val nav = linkedMapOf<Page, LinearLayout>()
 private val connectedControls = mutableListOf<Pair<View, String>>()
 private val featureRows = linkedMapOf<String, Pair<AdwaitaUi.Row, String>>()
 private val featureSwitches = mutableMapOf<String, Switch>()
 private var selected = Page.OVERVIEW
 private val stateChanged: () -> Unit = { refreshState() }
 private val preferencesChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { if (!isDestroyed) refreshState() } }

 override fun attachBaseContext(base: Context) {
  val mode = base.getSharedPreferences("settings", MODE_PRIVATE).getString("appearance", "system")
  val config = Configuration(base.resources.configuration)
  when (mode) {
   "light" -> config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
   "dark" -> config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
  }
  super.attachBaseContext(base.createConfigurationContext(config))
 }
 override fun onCreate(saved: Bundle?) {
  super.onCreate(saved)
  ui = AdwaitaUi(this)
  selected = runCatching { Page.valueOf(saved?.getString("page") ?: "OVERVIEW") }.getOrDefault(Page.OVERVIEW)
  val root = ui.column().apply { id = R.id.unity_root; setBackgroundColor(ui.window) }
  setContentView(root); setupInsets(root); buildHeader(root)
  val wide = resources.configuration.screenWidthDp / resources.configuration.fontScale >= 720
  val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
  if (wide) buildNavigation(body, true)
  content = FrameLayout(this); body.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
  buildOverview(); buildSharing(saved); buildFeatures(); buildSettings(saved)
  if (!wide) { root.addView(ui.divider()); buildNavigation(root, false) }
  select(selected); refreshState()
  if (saved == null) handleIntent(intent)
 }
 @Suppress("DEPRECATION")
 private fun setupInsets(root: View) {
  if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
  else @Suppress("DEPRECATION") run { window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION }
  @Suppress("DEPRECATION") run { window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT }
  root.setOnApplyWindowInsetsListener { view, insets ->
   if (Build.VERSION.SDK_INT >= 30) {
    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    val keyboard = insets.getInsets(WindowInsets.Type.ime())
    view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
   } else @Suppress("DEPRECATION") run { view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom) }
   insets
  }
  root.requestApplyInsets()
 }
 private fun buildHeader(root: LinearLayout) {
  val header = FrameLayout(this).apply { minimumHeight = ui.dp(64); setBackgroundColor(ui.surface) }
  header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_unity); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
   FrameLayout.LayoutParams(ui.dp(32), ui.dp(32), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = ui.dp(20) })
  headerTitle = ui.label("Overview", 17f, true).apply { gravity = Gravity.CENTER; isAccessibilityHeading = true }
  header.addView(headerTitle, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = ui.dp(64); rightMargin = ui.dp(64); topMargin = ui.dp(18); bottomMargin = ui.dp(18) })
  root.addView(header); root.addView(ui.divider())
 }
 private fun buildNavigation(parent: LinearLayout, wide: Boolean) {
  val panel = ui.column().apply { setBackgroundColor(ui.sidebar) }
  val items = LinearLayout(this).apply { orientation = if (wide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6)) }
  Page.entries.forEach { page ->
   val item = ui.navItem(page.title, page.icon, page.navId, wide) { select(page) }; nav[page] = item
   items.addView(item, if (wide) LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) } else LinearLayout.LayoutParams(0, -2, 1f))
  }
  panel.addView(items)
  if (wide) {
   panel.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
   panel.addView(ui.label("Local. Private. Connected.", 12f, secondary = true).apply { setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20)) })
   parent.addView(panel, LinearLayout.LayoutParams(ui.dp(232), -1)); parent.addView(ui.divider().apply { layoutParams = LinearLayout.LayoutParams(ui.dp(1), -1) })
  } else parent.addView(panel, LinearLayout.LayoutParams(-1, -2))
 }
 private fun page(page: Page): LinearLayout {
  val column = ClampedColumn(this).apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(8)) }
  val clamp = FrameLayout(this).apply { addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)) }
  val scroll = ScrollView(this).apply { id = page.pageId; isFillViewport = true; isHorizontalScrollBarEnabled = false; addView(clamp, ViewGroup.LayoutParams(-1, -2)); visibility = View.GONE }
  pages[page] = scroll; content.addView(scroll, FrameLayout.LayoutParams(-1, -1)); return column
 }
 private fun select(page: Page) {
  if (selected != page) getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(content.windowToken, 0)
  selected = page; headerTitle.text = page.title
  pages.forEach { (key, view) -> view.visibility = if (key == page) View.VISIBLE else View.GONE }
  nav.forEach { (key, view) -> ui.select(view, key == page) }
 }
 private fun safe(action: () -> Unit) { try { action() } catch (e: Exception) { message(e.message ?: "Action unavailable") } }
 private fun message(value: String) { runOnUiThread { if (!isDestroyed) Toast.makeText(this, value, Toast.LENGTH_LONG).show() } }
 private fun action(card: LinearLayout, title: String, description: String, icon: Int, id: Int? = null, feature: String? = null, block: () -> Unit): AdwaitaUi.Row {
  val row = ui.row(title, description, icon, action = { safe(block) }); if (id != null) row.view.id = id
  if (feature != null) connectedControls.add(row.view to feature)
  ui.addRow(card, row.view); return row
 }
 private fun buildOverview() {
  val page = page(Page.OVERVIEW)
  val hero = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(28)) }
  hero.addView(FrameLayout(this).apply {
   background = ui.shape(ui.accentSoft, 22); addView(ui.icon(R.drawable.ic_computer, ui.accentText, 40), FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER))
  }, LinearLayout.LayoutParams(ui.dp(80), ui.dp(80)).apply { bottomMargin = ui.dp(16) })
  connectionTitle = ui.label("Not connected", 24f, true).apply { id = R.id.connection_title; gravity = Gravity.CENTER; isAccessibilityHeading = true }
  connectionDetail = ui.label("", 14f, secondary = true).apply { id = R.id.connection_detail; gravity = Gravity.CENTER; setPadding(0, ui.dp(10), 0, ui.dp(16)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
  connectionBadge = ui.label("", 12f, true).apply { setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7)) }
  hero.addView(connectionTitle); hero.addView(connectionDetail); hero.addView(connectionBadge, LinearLayout.LayoutParams(-2, -2)); page.addView(hero)
  val computer = ui.section(page, "Your computer")
  computerRow = action(computer, "Add your computer", "Pair once. Stay connected locally.", R.drawable.ic_computer) { select(Page.SETTINGS) }
  val buttons = LinearLayout(this).apply { orientation = if (ui.compactActions) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; setPadding(0, 0, 0, ui.dp(24)) }
  connectButton = ui.button("Connect", true) { safe { connect() } }.apply { id = R.id.connect_button }
  disconnectButton = ui.button("Disconnect") { safe { stopService(Intent(this, CompanionService::class.java)); refreshState() } }.apply { id = R.id.disconnect_button }
  buttons.addView(connectButton, LinearLayout.LayoutParams(if (ui.compactActions) -1 else 0, -2, if (ui.compactActions) 0f else 1f))
  buttons.addView(disconnectButton, LinearLayout.LayoutParams(if (ui.compactActions) -1 else 0, -2, if (ui.compactActions) 0f else 1f).apply { if (ui.compactActions) topMargin = ui.dp(10) else marginStart = ui.dp(12) })
  page.addView(buttons)
  val quick = ui.section(page, "Quick actions")
  action(quick, "Send a file", "Choose a document, photo, or other file.", R.drawable.ic_file, feature = "files") { pickFile() }
  action(quick, "Send clipboard", "Share text while Unity is open.", R.drawable.ic_clipboard, feature = "clipboard") { sendClipboard() }
  action(quick, "Permissions and features", "Choose what your computer can access.", R.drawable.ic_shield) { select(Page.FEATURES) }
  page.addView(ui.label("Your connection keeps running when you leave this screen.", 13f, secondary = true).apply { gravity = Gravity.CENTER; setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(20)) })
 }
 private fun buildSharing(saved: Bundle?) {
  val page = page(Page.SHARING)
  val files = ui.section(page, "Files", "Files travel directly between your phone and computer.")
  action(files, "Send a file", "Choose a file with Android’s document picker.", R.drawable.ic_file, R.id.send_file_button, "files") { pickFile() }
  transferRow = ui.row("No transfer in progress", "Original files are sent only when you choose them.", R.drawable.ic_sharing); ui.addRow(files, transferRow.view)
  folderRow = action(files, "Shared folder", "Choose a folder for remote browsing.", R.drawable.ic_folder) { chooseFolder() }
  val clip = ui.section(page, "Clipboard", "You decide when text is sent or applied.")
  action(clip, "Send clipboard text", "Send your current clipboard to the computer.", R.drawable.ic_clipboard, R.id.send_clipboard_button, "clipboard") { sendClipboard() }
  receivedClipboard = action(clip, "Apply received text", "No text waiting.", R.drawable.ic_clipboard, R.id.received_clipboard_button) {
   val value = pending.getString("clipboard", null) ?: error("No received text")
   getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Unity", value)); pending.edit().remove("clipboard").apply(); message("Text copied to your clipboard")
  }
  val links = ui.section(page, "Web pages")
  url = ui.field(links, "URL to share", saved?.getString("url") ?: "", R.id.share_url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply { hint = "https://…"; setSingleLine() }
  action(links, "Share web page", "Open this page on your computer.", R.drawable.ic_link, R.id.send_url_button, "urls") { shareUrl(url.text.toString()) }
  receivedUrl = action(links, "Open received page", "No web page waiting.", R.drawable.ic_link, R.id.received_url_button) {
   val value = pending.getString("url", null) ?: error("No URL received"); startActivity(Intent(Intent.ACTION_VIEW, validUrl(value)))
  }
 }
 private fun buildFeatures() {
  val page = page(Page.FEATURES)
  page.addView(ui.label("Choose what to share", 24f, true).apply { isAccessibilityHeading = true; setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(10)) })
  page.addView(ui.label("Sensitive features start off. Choose what to share and review any Android permissions.", 14f, secondary = true).apply { setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(24)) })
  val notifications = ui.section(page, "Notifications")
  feature(notifications, "notifications", "Notification mirroring", "Show phone notifications on your computer.", R.drawable.ic_notification)
  feature(notifications, "notification_actions", "Replies and actions", "Allow supported replies and notification actions.", R.drawable.ic_message)
  notificationPermission = action(notifications, "Notification access", "Allow access in Android settings.", R.drawable.ic_shield) { settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
  val messages = ui.section(page, "Messages and calls")
  feature(messages, "sms", "Messages", "View and send SMS from your computer.", R.drawable.ic_message, R.id.feature_sms)
  feature(messages, "call_notifications", "Incoming calls", "Show incoming call information.", R.drawable.ic_phone)
  smsPermission = action(messages, "SMS and phone permissions", "Review the permissions requested by Unity.", R.drawable.ic_shield) { requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE), 2) }
  val sharing = ui.section(page, "Files and photos")
  feature(sharing, "files", "File transfers", "Send and receive files over your local network.", R.drawable.ic_file)
  feature(sharing, "photos", "Recent photos", "Share previews and selected original photos.", R.drawable.ic_photo)
  feature(sharing, "browsing", "Remote file browsing", "Only the folder you choose is accessible.", R.drawable.ic_folder)
  photoPermission = action(sharing, "Photo access", "Choose which photos Unity may share.", R.drawable.ic_shield) { photoPermissions() }
  action(sharing, "Choose shared folder", "Use Android’s folder picker.", R.drawable.ic_folder) { chooseFolder() }
  val clipboard = ui.section(page, "Clipboard and links")
  feature(clipboard, "clipboard", "Clipboard", "Manual sharing while the app is open.", R.drawable.ic_clipboard)
  feature(clipboard, "urls", "Web pages", "Share links with explicit open actions.", R.drawable.ic_link, sensitive = false)
  val media = ui.section(page, "Media")
  feature(media, "media", "Media controls", "Share track details, artwork, and playback controls.", R.drawable.ic_media)
  action(media, "Media access", "Uses notification access for active media sessions.", R.drawable.ic_shield) { settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
  val remote = ui.section(page, "Remote control")
  feature(remote, "remote_input", "Remote keyboard and touch", "Requires your explicit keyboard or accessibility setup.", R.drawable.ic_keyboard)
  feature(remote, "find", "Find phone", "Allow your computer to ring this phone.", R.drawable.ic_phone, sensitive = false)
  feature(remote, "ringer", "Ringer and Do Not Disturb", "Allow supported sound-mode changes.", R.drawable.ic_notification, sensitive = false)
  dndPermission = action(remote, "Do Not Disturb access", "Review policy access in Android settings.", R.drawable.ic_shield) { settings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
  action(remote, "Enable Unity keyboard", "Enable the keyboard in Android settings.", R.drawable.ic_keyboard) { settings(Settings.ACTION_INPUT_METHOD_SETTINGS) }
  action(remote, "Select keyboard", "Unity must be selected for remote typing.", R.drawable.ic_keyboard) { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
  action(remote, "Remote touch access", "Review Unity’s accessibility service.", R.drawable.ic_shield) { settings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
  val network = ui.section(page, "Internet sharing")
  feature(network, "hotspot", "Sharing requests", "Computer requests open a review prompt on your phone.", R.drawable.ic_wifi, sensitive = false)
  action(network, "Internet sharing settings", "You choose whether to enable tethering.", R.drawable.ic_wifi) { settings(Settings.ACTION_WIRELESS_SETTINGS) }
  val unavailable = ui.section(page, "Not available in this build")
  ui.addRow(unavailable, ui.row("Bluetooth transport", "Secure LAN is supported. BLE communication is not implemented.", R.drawable.ic_info).view)
  ui.addRow(unavailable, ui.row("Call answering", "Requires a supported dialer or telephony integration.", R.drawable.ic_phone).view)
  ui.addRow(unavailable, ui.row("Screen mirroring", "Capture, encoding, and rendering are not implemented.", R.drawable.ic_computer).view)
 }
 private fun feature(card: LinearLayout, id: String, title: String, description: String, icon: Int, viewId: Int? = null, sensitive: Boolean = true) {
  val toggle = ui.toggle(prefs.getBoolean("enable.$id", !sensitive), title) { checked -> prefs.edit().putBoolean("enable.$id", checked).apply(); CompanionService.instance?.features?.publish(); refreshState() }
  if (viewId != null) toggle.id = viewId
  if (id == "notifications") toggle.id = R.id.feature_notifications
  featureSwitches[id] = toggle
  val row = ui.row(title, description, icon, toggle); featureRows[id] = row to description; ui.addRow(card, row.view)
 }
 private fun buildSettings(saved: Bundle?) {
  val page = page(Page.SETTINGS)
  val connection = ui.section(page, "Computer connection", "Use the same local network. Compare the full TLS fingerprint shown in Unity on your computer before pairing.")
  host = ui.field(connection, "Computer address", saved?.getString("host") ?: prefs.getString("host", "")!!, R.id.computer_host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply { hint = "192.168.1.10"; setSingleLine() }
  port = ui.field(connection, "Port", saved?.getString("port") ?: prefs.getInt("port", 17161).toString(), R.id.computer_port, InputType.TYPE_CLASS_NUMBER).apply { setSingleLine() }
  fingerprint = ui.field(connection, "Verified TLS fingerprint", saved?.getString("fingerprint") ?: prefs.getString("fingerprint", "")!!, R.id.computer_fingerprint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
   hint = "64 hexadecimal digits"; minLines = 2; maxLines = 8; textSize = 14f
   setHorizontallyScrolling(false); typeface = android.graphics.Typeface.MONOSPACE
  }
  action(connection, "Save and connect", "Approve a new pairing request on your computer.", R.drawable.ic_check) { connect() }
  action(connection, "Forget this computer", "Remove this phone’s saved address and pairing credential.", R.drawable.ic_shield, R.id.forget_computer_row) { forgetComputer() }
  val appearance = ui.section(page, "Appearance")
  action(appearance, "Style", when (prefs.getString("appearance", "system")) { "light" -> "Light"; "dark" -> "Dark"; else -> "Follow system" }, R.drawable.ic_overview, R.id.appearance_row) { appearance() }
  val privacy = ui.section(page, "Notification privacy", "Excluded apps will not send new notifications to your computer.")
  excluded = ui.field(privacy, "Excluded app packages", saved?.getString("excluded") ?: prefs.getString("notification_excluded", "")!!, R.id.excluded_apps, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { hint = "com.example.private, com.example.work"; minLines = 2; maxLines = 5; setHorizontallyScrolling(false) }
  action(privacy, "Save exclusions", "Separate Android package names with commas.", R.drawable.ic_check) { prefs.edit().putString("notification_excluded", excluded.text.toString().take(4096)).apply(); message("Notification exclusions saved") }
  val about = ui.section(page, "About Unity")
  ui.addRow(about, ui.row("Local. Private. Connected.", "No cloud account, external server, or telemetry.", R.drawable.ic_shield).view)
  ui.addRow(about, ui.row("Android companion", "Version ${packageManager.getPackageInfo(packageName, 0).versionName} · Unity protocol 1", R.drawable.ic_info).view)
 }
 private fun refreshState() {
  if (!::host.isInitialized) return
  val service = CompanionService.instance; val state = service?.uiState ?: ConnectionState.disconnected; val ready = service?.connection?.ready == true
  val transfer = service?.transferState ?: TransferState()
  connectionTitle.text = state.title; connectionDetail.text = state.detail
  connectionBadge.text = if (ready) "Encrypted · Local network" else "Local connection"
  connectionBadge.setTextColor(if (ready) ui.success else ui.muted); connectionBadge.background = ui.shape(if (ready) ui.successSoft else ui.sidebar, 20)
  val address = prefs.getString("host", "").orEmpty(); computerRow.title.text = address.ifBlank { "Add your computer" }
  ui.describe(computerRow, if (address.isBlank()) "Pair once. Stay connected locally." else if (ready) "Connected over Wi-Fi / LAN" else "${state.title} · Tap to review connection settings")
  connectButton.text = if (address.isBlank()) "Set up computer" else if (service != null) "Reconnect" else "Connect"
  connectButton.isEnabled = state.phase !in listOf(ConnectionPhase.CONNECTING, ConnectionPhase.PAIRING); disconnectButton.isEnabled = service != null
  connectedControls.forEach { (view, feature) ->
   val enabled = ready && service.features.modules[feature]?.capability()?.optString("status") in listOf("available", "user_action_required")
   view.isEnabled = enabled && (feature != "files" || !transfer.busy); view.alpha = if (view.isEnabled) 1f else .45f
  }
  receivedClipboard.view.isEnabled = pending.contains("clipboard"); receivedClipboard.view.alpha = if (receivedClipboard.view.isEnabled) 1f else .45f
  ui.describe(receivedClipboard, if (pending.contains("clipboard")) "Text is waiting. Tap to copy it on this phone." else "No text waiting.")
  receivedUrl.view.isEnabled = pending.contains("url"); receivedUrl.view.alpha = if (receivedUrl.view.isEnabled) 1f else .45f; ui.describe(receivedUrl, pending.getString("url", "No web page waiting.")!!)
  ui.describe(folderRow, if (prefs.contains("tree")) "A folder is selected. Tap to change it." else "Choose a folder for remote browsing.")
  val listener = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(this, PhoneNotifications::class.java))
  ui.describe(notificationPermission, if (listener) "Allowed in Android settings." else "Not allowed. Tap to review Android settings.")
  ui.describe(smsPermission, if (granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.SEND_SMS) && granted(Manifest.permission.READ_PHONE_STATE)) "Permissions granted." else "Some permissions are missing. Tap to review.")
  ui.describe(photoPermission, if (photoGranted()) "Photo access granted. Enable file transfers to download originals." else "Not allowed. Choose which photos may be shared.")
  ui.describe(dndPermission, if (getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) "Policy access allowed." else "Not allowed. Tap to review Android settings.")
  featureRows.forEach { (id, value) ->
   val (row, description) = value; val toggle = featureSwitches.getValue(id); val enabled = prefs.getBoolean("enable.$id", id !in sensitive)
   if (toggle.isChecked != enabled) toggle.isChecked = enabled
   val capability = service?.features?.modules?.get(id)?.capability()
   val summary = when { !enabled -> null; capability != null && capability.optString("status") == "available" -> "Ready"; capability != null -> capability.optString("reason"); else -> permissionHint(id, listener) ?: "Ready to connect" }
   ui.describe(row, if (summary == null) description else "$description\n$summary")
  }
  transferRow.title.text = transfer.title
  ui.describe(transferRow, if (transfer.busy) "Keep the local connection available until the transfer finishes." else if (transfer.failed) "Check your connection and try again." else "Original files are sent only when you choose them.")
 }
 private fun connect() {
  if (host.text.isBlank() || fingerprint.text.isBlank()) { select(Page.SETTINGS); if (host.text.isBlank()) host.requestFocus() else fingerprint.requestFocus(); message("Enter your computer address and verified TLS fingerprint"); return }
  val pin = fingerprint.text.toString().lowercase().filterNot { it == ':' || it.isWhitespace() }
  require(pin.length == 64 && Protocol.unhex(pin).size == 32) { "Paste the verified 64-digit TLS fingerprint" }
  val address = host.text.toString().trim(); require(address.isNotBlank() && address.length <= 253 && address.none { it.isWhitespace() || it.code < 32 }) { "Enter a valid computer address" }
  val number = port.text.toString().toIntOrNull(); require(number != null && number in 1024..65535) { "Port must be between 1024 and 65535" }
  val oldPin = prefs.getString("fingerprint", null)
  if (oldPin != null && oldPin != pin) AlertDialog.Builder(this).setTitle("Replace your trusted computer?")
   .setMessage("This TLS identity differs from the saved computer. Verify the full new fingerprint on your computer. Continuing removes the old phone pairing credential and requires approval again.")
   .setNegativeButton("Cancel", null).setPositiveButton("Replace and pair") { _, _ -> safe { Credentials(this).clear(); saveConnection(address, number, pin) } }.show()
  else saveConnection(address, number, pin)
 }
 private fun saveConnection(address: String, number: Int, pin: String) {
  check(prefs.edit().putString("host", address).putInt("port", number).putString("fingerprint", pin).commit()) { "Could not save connection settings" }
  if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
  startForegroundService(Intent(this, CompanionService::class.java).setAction("connect")); select(Page.OVERVIEW)
 }
 private fun forgetComputer() {
  AlertDialog.Builder(this).setTitle("Forget this computer?").setMessage("The connection will stop and this phone’s saved pairing credential will be removed. Remove the phone from Unity on the computer to revoke its saved trust there too.")
   .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ -> safe {
    stopService(Intent(this, CompanionService::class.java)); Credentials(this).clear(); prefs.edit().remove("host").remove("fingerprint").remove("port").apply()
    host.setText(""); fingerprint.setText(""); port.setText(String.format(java.util.Locale.ROOT, "%d", 17161)); refreshState()
   } }.show()
 }
 private fun appearance() {
  val options = arrayOf("Follow system", "Light", "Dark"); val values = arrayOf("system", "light", "dark")
  AlertDialog.Builder(this).setTitle("Appearance").setSingleChoiceItems(options, values.indexOf(prefs.getString("appearance", "system"))) { dialog, index -> prefs.edit().putString("appearance", values[index]).apply(); dialog.dismiss(); recreate() }.setNegativeButton("Cancel", null).show()
 }
 private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
 private fun permissionHint(id: String, listener: Boolean): String? = when {
  id in listOf("notifications", "notification_actions", "media") && !listener -> "Allow notification access"
  id == "notification_actions" && !prefs.getBoolean("enable.notifications", false) -> "Enable notification mirroring"
  id == "sms" && (!granted(Manifest.permission.READ_SMS) || !granted(Manifest.permission.SEND_SMS)) -> "Allow SMS permissions"
  id == "call_notifications" && !granted(Manifest.permission.READ_PHONE_STATE) -> "Allow phone permission"
  id in listOf("photos", "browsing") && !prefs.getBoolean("enable.files", false) -> "Enable file transfers"
  id == "photos" && !photoGranted() -> "Choose which photos may be shared"
  id == "browsing" && !prefs.contains("tree") -> "Choose a shared folder"
  id == "ringer" && !getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted -> "Allow Do Not Disturb access"
  id == "remote_input" && UnityKeyboard.instance == null && UnityAccessibility.instance == null -> "Select Unity keyboard or enable remote touch access"
  id == "hotspot" -> "Phone approval required for each request"
  else -> null
 }
 private fun photoGranted(): Boolean {
  val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
  return granted(permission) || (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
 }
 private fun photoPermissions() {
  val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
  requestPermissions(if (Build.VERSION.SDK_INT >= 34) arrayOf(permission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else arrayOf(permission), 3)
 }
 private fun settings(action: String) { startActivity(Intent(action)) }
 private fun chooseFolder() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 10) }
 private fun pickFile() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 11) }
 private fun sendClipboard() {
  val service = CompanionService.instance ?: error("Connect first"); check(service.features.modules.getValue("clipboard").capability().getString("status") == "available") { "Enable clipboard sharing" }
  val value = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: error("Clipboard is empty")
  require(value.toByteArray().size <= 64 * 1024 - 256) { "Clipboard text is too large" }
  service.event("clipboard", JSONObject().put("text", value)); message("Clipboard text sent")
 }
 private fun validUrl(value: String): Uri {
  val uri = Uri.parse(value); require(value.length <= 8192 && value.none { it.code < 32 } && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "Enter a valid http or https web address" }; return uri
 }
 private fun shareUrl(value: String) {
  validUrl(value); val service = CompanionService.instance ?: error("Connect first")
  check(service.features.modules.getValue("urls").capability().getString("status") == "available") { "Enable web page sharing" }
  service.event("urls", JSONObject().put("url", value)); message("Web page shared")
 }
 private fun handleIntent(intent: Intent?) {
  when (intent?.getStringExtra("phone_action")) { "hotspot" -> safe { settings(Settings.ACTION_WIRELESS_SETTINGS) }; "clipboard", "url" -> select(Page.SHARING) }
  if (intent?.action == Intent.ACTION_SEND) {
   select(Page.SHARING)
   val text = intent.getStringExtra(Intent.EXTRA_TEXT)
   val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
   if (uri != null) sendFile(uri) else if (text != null) { url.setText(text.take(8192)); safe { shareUrl(text) } }
  }
 }
 private fun sendFile(uri: Uri) {
  safe { (CompanionService.instance ?: error("Connect first")).sendFile(uri); select(Page.SHARING); refreshState() }
 }
 override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
  super.onActivityResult(requestCode, resultCode, data); if (resultCode != RESULT_OK) return
  val uri = data?.data ?: return
  safe { if (requestCode == 10) { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); prefs.edit().putString("tree", uri.toString()).apply(); CompanionService.instance?.features?.publish(); refreshState(); message("Shared folder updated") } else if (requestCode == 11) sendFile(uri) }
 }
 override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
 override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) { super.onRequestPermissionsResult(code, permissions, results); CompanionService.instance?.features?.publish(); refreshState() }
 override fun onSaveInstanceState(out: Bundle) {
  out.putString("page", selected.name); listOf("host" to host, "port" to port, "fingerprint" to fingerprint, "url" to url, "excluded" to excluded).forEach { (key, field) -> out.putString(key, field.text.toString()) }; super.onSaveInstanceState(out)
 }
 override fun onStart() {
  super.onStart(); CompanionEvents.observe(stateChanged)
  prefs.registerOnSharedPreferenceChangeListener(preferencesChanged); pending.registerOnSharedPreferenceChangeListener(preferencesChanged); refreshState()
 }
 override fun onStop() {
  CompanionEvents.remove(stateChanged)
  prefs.unregisterOnSharedPreferenceChangeListener(preferencesChanged); pending.unregisterOnSharedPreferenceChangeListener(preferencesChanged); super.onStop()
 }
 override fun onResume() { super.onResume(); CompanionService.instance?.features?.publish(); refreshState() }
 companion object { private val sensitive = setOf("notifications", "notification_actions", "sms", "call_notifications", "clipboard", "files", "photos", "browsing", "media", "remote_input") }
}
