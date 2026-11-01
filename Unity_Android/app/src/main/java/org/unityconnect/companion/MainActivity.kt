package org.unityconnect.companion

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONObject

/** Companion UI. Connections and feature execution remain in the service. */
class MainActivity : AdwaitaActivity() {
 private enum class Page(val title: String, val icon: Int, val navId: Int, val pageId: Int) {
  OVERVIEW("Overview", R.drawable.ic_overview, R.id.nav_overview, R.id.page_overview),
  SHARING("Sharing", R.drawable.ic_sharing, R.id.nav_sharing, R.id.page_sharing),
  FEATURES("Features", R.drawable.ic_features, R.id.nav_features, R.id.page_features),
  SETTINGS("Settings", R.drawable.ic_settings, R.id.nav_settings, R.id.page_settings)
 }
 private lateinit var networkPrivacyRow: AdwaitaUi.Row
 private lateinit var networkConnectionRow: AdwaitaUi.Row
 private lateinit var authenticationSetup: AdwaitaUi.Row
 private var networkPrivacyDialog: AlertDialog? = null
 private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
 private val pending by lazy { getSharedPreferences("pending", MODE_PRIVATE) }
 private lateinit var ui: AdwaitaUi
 private lateinit var host: EditText
 private lateinit var bluetoothAddress: EditText
 private lateinit var transportRow: AdwaitaUi.Row
 private var transportMode = "both"
 private var resumeBluetoothConnect = false
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
 private lateinit var receivedNotificationPermission: AdwaitaUi.Row
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
 private var resumed = false
 private lateinit var screenStatus: AdwaitaUi.Row
 private var selected = Page.OVERVIEW
 private val stateChanged: () -> Unit = { refreshState() }
 private val preferencesChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { if (!isDestroyed) refreshState() } }

 override fun onCreate(saved: Bundle?) {
  super.onCreate(saved)
  ui = AdwaitaUi(this)
  selected = runCatching { Page.valueOf(saved?.getString("page") ?: "OVERVIEW") }.getOrDefault(Page.OVERVIEW)
  val root = ui.column().apply { id = R.id.unity_root; setBackgroundColor(ui.window) }
  setContentView(root); applyInsets(root); buildHeader(root)
  val wide = resources.configuration.screenWidthDp / resources.configuration.fontScale >= 720
  val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
  if (wide) buildNavigation(body, true)
  content = FrameLayout(this); body.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
  transportMode = saved?.getString("transport") ?: prefs.getString("transport", "both").orEmpty()
  buildOverview(); buildSharing(saved); buildFeatures(); buildSettings(saved)
  if (!wide) { root.addView(ui.divider()); buildNavigation(root, false) }
  select(selected); refreshState()
  if (saved == null || intent?.getStringExtra("phone_action")=="authentication") handleIntent(intent)
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
  action(quick, "Media players", "View and control laptop and phone players.", R.drawable.ic_media, R.id.open_media_players) { startActivity(Intent(this, MediaActivity::class.java)) }
  action(quick, "Send a file", "Choose a document, photo, or other file.", R.drawable.ic_file, feature = "files") { pickFile() }
  action(quick, "Automatic clipboard sync", "Clipboard text syncs without send or receive buttons.", R.drawable.ic_clipboard) { select(Page.SHARING) }
  action(quick, "Lock computer", "Lock your paired computer’s current session.", R.drawable.ic_shield, R.id.lock_computer_button, "lock") { desktopRequest("lock", "lock") }
  action(quick, "Permissions and features", "Choose what your computer can access.", R.drawable.ic_shield) { select(Page.FEATURES) }
  page.addView(ui.label("Your connection keeps running when you leave this screen.", 13f, secondary = true).apply { gravity = Gravity.CENTER; setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(20)) })
 }
 private fun buildSharing(saved: Bundle?) {
  val page = page(Page.SHARING)
  val screens = ui.section(page, "Screen sharing", "The device sharing its screen asks for approval each time.")
  action(screens, "Share phone screen", "View this phone on your computer.", R.drawable.ic_computer, R.id.share_phone_screen, "screen") { desktopRequest("screen", "start", JSONObject().put("source", "phone")) }
  action(screens, "View and control computer screen", "Approve a screen and keyboard/mouse access on your computer. Touch to click; use the keyboard icon to type.", R.drawable.ic_computer, R.id.view_computer_screen, "screen") { desktopRequest("screen", "start", JSONObject().put("source", "desktop").put("control", true)) }
  action(screens, "Use phone as extended display", "Keep the laptop screen on and use this phone as a second monitor. Move windows between displays with your computer’s mouse or keyboard.", R.drawable.ic_computer, R.id.view_computer_extended_display, "screen") { desktopRequest("screen", "start", JSONObject().put("source", "desktop").put("control", true).put("extend_display", true)) }
  action(screens, "Use phone as main display", "Make this phone the computer’s primary display and disable the laptop panel. The previous display layout returns when sharing stops.", R.drawable.ic_computer, R.id.view_computer_display_off, "screen") { desktopRequest("screen", "start", JSONObject().put("source", "desktop").put("control", true).put("blank_display", true)) }
  action(screens, "Stop screen sharing", "End capture and close the encrypted stream.", R.drawable.ic_shield, R.id.stop_screen_share) { CompanionService.instance?.features?.screen?.stopRemote() }
  screenStatus = ui.row("No screen share", "Enable Screen sharing in Features, then connect.", R.drawable.ic_computer); ui.addRow(screens, screenStatus.view)
  val files = ui.section(page, "Files", "Files travel directly between your phone and computer.")
  action(files, "Send a file", "Choose a file to send. Received files are saved in Downloads/Unity.", R.drawable.ic_file, R.id.send_file_button, "files") { pickFile() }
  transferRow = ui.row("No transfer in progress", "Original files are sent only when you choose them.", R.drawable.ic_sharing); ui.addRow(files, transferRow.view)
  folderRow = action(files, "Shared folder", "Choose a folder for remote browsing.", R.drawable.ic_folder) { chooseFolder() }
  val clip = ui.section(page, "Clipboard", "Text syncs automatically while connected. No send or receive action is needed.")
  receivedClipboard = ui.row("Automatic clipboard sync", "Connect to sync clipboard text.", R.drawable.ic_clipboard)
  receivedClipboard.view.id = R.id.received_clipboard_button; ui.addRow(clip, receivedClipboard.view)
  action(clip, "Background clipboard access", "Keep your current keyboard. Start Shizuku, then allow Unity to read clipboard changes in the background.", R.drawable.ic_clipboard) {
   val service = CompanionService.instance ?: error("Connect to your computer first")
   ui.dialog().setTitle("Background clipboard access")
    .setMessage("${service.features.clipboard.backgroundStatus()}\n\nInstall and start Shizuku using its wireless debugging or computer setup. Return here and allow Unity. On non-rooted phones, restart Shizuku after reboot. Clipboard sharing stays under the Clipboard switch in Features.")
    .setPositiveButton("Set up / Allow") { _, _ -> safe { service.features.clipboard.requestBackgroundAccess() } }
    .setNeutralButton("Setup guide") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))) }
    .setNegativeButton("Cancel", null).show()
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
  page.addView(ui.label("Clipboard sync starts on. Choose other features to share and review any Android permissions.", 14f, secondary = true).apply { setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(24)) })
  val notifications = ui.section(page, "Notifications")
  feature(notifications, "notifications", "Notification mirroring", "Show phone notifications on your computer.", R.drawable.ic_notification)
  feature(notifications, "notification_actions", "Replies and actions", "Allow supported replies and notification actions.", R.drawable.ic_message)
  receivedNotificationPermission = action(notifications, "Received file, link and text alerts", "Tap to allow notifications or change their settings.", R.drawable.ic_notification, R.id.received_notification_settings) {
   if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS) && !prefs.getBoolean("notification_permission_requested", false)) requestNotificationPermission()
   else {
    ReceivedItems.channel(this)
    val settings = if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
    startActivity(Intent(settings).putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, ReceivedItems.CHANNEL))
   }
  }
  notificationPermission = action(notifications, "Notification access", "Allow access in Android settings.", R.drawable.ic_shield) { settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
  val messages = ui.section(page, "Messages and calls")
  feature(messages, "sms", "Messages", "View and send SMS from your computer.", R.drawable.ic_message, R.id.feature_sms)
  feature(messages, "call_notifications", "Incoming calls", "Show incoming call information.", R.drawable.ic_phone)
  feature(messages, "call_control", "Call management", "Use your current Phone app. Your computer handles call controls and audio over paired Bluetooth hands-free; Unity shares contacts and recent calls with your permission.", R.drawable.ic_phone)
  action(messages, "Set up call management", "Pair the computer for Bluetooth calls and allow phone, contacts and history access.", R.drawable.ic_phone) {
   requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE), 44)
  }
  action(messages, "Contacts and call history permissions", "Allow contact search, recent calls and placing calls.", R.drawable.ic_shield) { requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE), 44) }
  action(messages, "Phone dial pad", "Make calls and view recent calls on this phone.", R.drawable.ic_phone) { startActivity(Intent(this, DialerActivity::class.java)) }
  action(messages, "Enable phone speakerphone controls", "Optional: choose Unity as your default Phone app for earpiece, speakerphone and Bluetooth controls on your computer. Unity then provides the phone’s call screen too.", R.drawable.ic_phone) {
   val role = getSystemService(android.app.role.RoleManager::class.java)
   if (role.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER)) {
    ui.dialog().setTitle("Use Unity as your Phone app?")
     .setMessage("Android requires the default Phone app to change speakerphone during cellular calls. Unity will show incoming and ongoing calls on this phone, including while disconnected. You can restore your previous Phone app in Android’s Default apps settings.")
     .setNegativeButton("Cancel", null).setPositiveButton("Choose Phone app") { _, _ -> startActivityForResult(role.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER), 45) }.show()
   } else message("This phone does not support changing its default Phone app")
  }
  smsPermission = action(messages, "SMS and phone permissions", "Review the permissions requested by Unity.", R.drawable.ic_shield) { requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE), 2) }
  val sharing = ui.section(page, "Files and photos")
  feature(sharing, "files", "File transfers", "Send and receive files over your local network.", R.drawable.ic_file)
  feature(sharing, "photos", "Photos and videos", "Share photo/video thumbnails and selected originals.", R.drawable.ic_photo)
  feature(sharing, "browsing", "Remote file browsing", "Only the folder you choose is accessible.", R.drawable.ic_folder)
  photoPermission = action(sharing, "Photo and video access", "Choose which photos and videos Unity may share.", R.drawable.ic_shield) { photoPermissions() }
  action(sharing, "Choose shared folder", "Use Android’s folder picker.", R.drawable.ic_folder) { chooseFolder() }
  val clipboard = ui.section(page, "Clipboard and links")
  feature(clipboard, "clipboard", "Clipboard", "Automatically sync text with your paired computer.", R.drawable.ic_clipboard)
  feature(clipboard, "urls", "Web pages", "Share links with explicit open actions.", R.drawable.ic_link, sensitive = false)
  val media = ui.section(page, "Media")
  feature(media, "media", "Media and sound controls", "Share track details, playback controls and device volume.", R.drawable.ic_media)
  feature(media, "audio", "Phone and laptop audio", "Stream sound in either direction over Wi-Fi or Bluetooth. Choose direction and connection on the computer’s Media page; phone capture asks for approval each time.", R.drawable.ic_media, R.id.feature_audio)
  action(media, "Open media players", "Choose and control a laptop or phone player.", R.drawable.ic_media) { startActivity(Intent(this, MediaActivity::class.java)) }
  action(media, "Media access", "Uses notification access for active media sessions.", R.drawable.ic_shield) { settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
  val remote = ui.section(page, "Remote control")
  feature(remote, "authentication", "Laptop fingerprint approval", "Approve laptop login and sudo with a fresh phone fingerprint. Requires one-time administrator enrollment; your laptop password stays available.", R.drawable.ic_shield, R.id.feature_authentication)
  authenticationSetup = action(remote, "Set up fingerprint approval", "Create and export your credential for laptop enrollment.", R.drawable.ic_shield, R.id.open_authentication) {
   val request=CompanionService.instance?.features?.authentication?.requestId()
   startActivity(if (request!=null) PhoneAuthenticationActivity.reviewIntent(this,request) else Intent(this, PhoneAuthenticationActivity::class.java))
  }
  feature(remote, "lock", "Remote device locking", "Lock your computer here, or let your computer lock this phone.", R.drawable.ic_shield, R.id.feature_lock)
  action(remote, "Activate phone locking", "Android grants only the screen-lock policy. You can remove it in device-admin settings.", R.drawable.ic_shield, R.id.activate_phone_lock) { startActivity(DeviceLock.activation(this)) }
  feature(remote, "screen", "Screen sharing", "Share and view screens in either direction, with capture approval each time.", R.drawable.ic_computer, R.id.feature_screen)
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
  val connectivity = ui.section(page, "Phone connectivity")
  feature(connectivity, "phone_connectivity", "Phone connectivity", "Share phone mobile hotspot state and connected Bluetooth device names and addresses with your computer.", R.drawable.ic_wifi, R.id.feature_phone_connectivity)
  action(connectivity, "Bluetooth device permission", "Allow Nearby devices so Unity can report connected Bluetooth devices.", R.drawable.ic_shield, R.id.phone_bluetooth_permission) {
   if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 49)
   else message("Bluetooth access is included on this Android version")
  }
  action(connectivity, "Connected Bluetooth devices", "View the phone’s devices and their Samsung disconnect routine keywords.", R.drawable.ic_link, R.id.phone_bluetooth_devices) {
   safe {
    val service = CompanionService.instance ?: error("Connect to your computer first")
    val state = service.features.phoneConnectivity.snapshot(); val devices = state.getJSONArray("devices")
    val details = (0 until devices.length()).joinToString("\n\n") { index ->
     val device = devices.getJSONObject(index)
     "${device.getString("name")}\n${device.getString("address")}\n${SamsungRoutines.deviceKeyword(device.getString("address"))}"
    }
    ui.dialog().setTitle("Connected Bluetooth devices").setMessage(state.getString("reason") + "\n\n" +
     details.ifEmpty { "No connected devices are currently reported." } +
     "\n\nFor each device you want to disconnect remotely, create a Samsung Notification received routine for its exact keyword and select that same device in the disconnect action.")
     .setPositiveButton("OK", null).show()
   }
  }
  if (SamsungRoutines.supported()) {
   val routines = ui.section(page, "Samsung Routines")
   feature(routines, "samsung_routines", "Samsung Routines", "Send mobile hotspot and device-specific Bluetooth disconnect keywords to your configured phone routines.", R.drawable.ic_wifi, R.id.feature_samsung_routines)
   action(routines, "Set up Samsung Routines", "Choose the Bluetooth device in Samsung’s Disconnect from a Bluetooth device action.", R.drawable.ic_link, R.id.setup_samsung_routines) { samsungRoutinesSetup() }
   action(routines, "Routine notification settings", "Allow Unity notifications and the Samsung Routines triggers category.", R.drawable.ic_notification) {
    if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) requestNotificationPermission()
    else {
     SamsungRoutines.channel(this)
     val action = if (getSystemService(NotificationManager::class.java).areNotificationsEnabled()) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS
     startActivity(Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, SamsungRoutines.CHANNEL))
    }
   }
  }
 }
 private fun samsungRoutinesSetup() {
  ui.dialog().setTitle("Set up Samsung Routines").setMessage(SamsungRoutines.setup(this)).setPositiveButton("OK", null).show()
 }
 private fun feature(card: LinearLayout, id: String, title: String, description: String, icon: Int, viewId: Int? = null, sensitive: Boolean = true) {
  val toggle = ui.toggle(prefs.getBoolean("enable.$id", id == "clipboard" || !sensitive), title) { checked -> prefs.edit().putBoolean("enable.$id", checked).apply(); CompanionService.instance?.features?.publish(); refreshState() }
  if (viewId != null) toggle.id = viewId
  if (id == "notifications") toggle.id = R.id.feature_notifications
  featureSwitches[id] = toggle
  val row = ui.row(title, description, icon, toggle); featureRows[id] = row to description; ui.addRow(card, row.view)
 }
 private fun buildSettings(saved: Bundle?) {
  val page = page(Page.SETTINGS)
  val connection = ui.section(page, "Computer connection", "Keep Bluetooth and Wi-Fi / LAN connected together. Controls use Bluetooth; files and streams use Wi-Fi / LAN. Compare the full TLS fingerprint shown on your computer before pairing.")
  transportRow = action(connection, "Connection method", "", R.drawable.ic_link, R.id.connection_method) {
   ui.dialog().setTitle("Connection method").setSingleChoiceItems(arrayOf("Bluetooth + Wi-Fi / LAN", "Wi-Fi / LAN only", "Bluetooth only"), when (transportMode) { "lan" -> 1; "bluetooth" -> 2; else -> 0 }) { dialog, index ->
    transportMode = when (index) { 1 -> "lan"; 2 -> "bluetooth"; else -> "both" }; dialog.dismiss(); refreshConnectionFields()
   }.setNegativeButton("Cancel", null).show()
  }
  bluetoothAddress = ui.field(connection, "Computer Bluetooth address", saved?.getString("bluetooth_address") ?: prefs.getString("bluetooth_address", "").orEmpty(), R.id.bluetooth_address,
   InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply { hint = "AA:BB:CC:DD:EE:FF"; setSingleLine() }
  action(connection, "Choose Bluetooth computer", "Pair the computer in Android Bluetooth settings, then choose it here.", R.drawable.ic_computer, R.id.choose_bluetooth_computer) { chooseBluetoothComputer() }
  action(connection, "Bluetooth system settings", "Turn on Bluetooth and pair your phone and computer.", R.drawable.ic_settings) { settings(Settings.ACTION_BLUETOOTH_SETTINGS) }
  host = ui.field(connection, "Computer address", saved?.getString("host") ?: prefs.getString("host", "")!!, R.id.computer_host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply { hint = "192.168.1.10"; setSingleLine() }
  port = ui.field(connection, "Port", saved?.getString("port") ?: prefs.getInt("port", 17161).toString(), R.id.computer_port, InputType.TYPE_CLASS_NUMBER).apply { setSingleLine() }
  fingerprint = ui.field(connection, "Verified TLS fingerprint", saved?.getString("fingerprint") ?: prefs.getString("fingerprint", "")!!, R.id.computer_fingerprint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
   hint = "64 hexadecimal digits"; minLines = 2; maxLines = 8; textSize = 14f
   setHorizontallyScrolling(false); typeface = android.graphics.Typeface.MONOSPACE
  }
  val networkSafety = ui.section(page, "Network privacy", "All device data uses verified TLS 1.3 on every network. Bluetooth address updates replace plaintext discovery broadcasts.")
  networkPrivacyRow = action(networkSafety, "Review current Wi-Fi", "Public or private is unknown. Encryption stays on either way.", R.drawable.ic_shield, R.id.network_privacy) { reviewNetworkPrivacy(force = true) }
  networkConnectionRow = ui.row("Wi-Fi connection", "Connect to view network status.", R.drawable.ic_wifi); ui.addRow(networkSafety, networkConnectionRow.view)
  action(networkSafety, "Wi-Fi sign-in and settings", "Open Wi-Fi settings and select the network’s sign-in notification if needed.", R.drawable.ic_wifi) { settings(Settings.ACTION_WIFI_SETTINGS) }
  action(connection, "Save and connect", "Approve a new pairing request on your computer.", R.drawable.ic_check) { connect() }
  action(connection, "Forget this computer", "Remove this phone’s saved address and pairing credential.", R.drawable.ic_shield, R.id.forget_computer_row) { forgetComputer() }
  val appearance = ui.section(page, "Appearance")
  action(appearance, "Style", when (prefs.getString("appearance", "system")) { "light" -> "Light"; "dark" -> "Dark"; else -> "Follow system" }, R.drawable.ic_overview, R.id.appearance_row) { appearance() }
  val privacy = ui.section(page, "Notification privacy", "Excluded apps will not send new notifications to your computer.")
  excluded = ui.field(privacy, "Excluded app packages", saved?.getString("excluded") ?: prefs.getString("notification_excluded", "")!!, R.id.excluded_apps, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { hint = "com.example.private, com.example.work"; minLines = 2; maxLines = 5; setHorizontallyScrolling(false) }
  action(privacy, "Save exclusions", "Separate Android package names with commas.", R.drawable.ic_check) { prefs.edit().putString("notification_excluded", excluded.text.toString().take(4096)).apply(); message("Notification exclusions saved") }
  refreshConnectionFields()
  val about = ui.section(page, "About ${getString(R.string.app_name)}")
  ui.addRow(about, ui.row("Local. Private. Connected.", "No cloud account, external server, or telemetry.", R.drawable.ic_shield).view)
  ui.addRow(about, ui.row("Android companion", "Version ${packageManager.getPackageInfo(packageName, 0).versionName} · Unity protocol 1", R.drawable.ic_info).view)
 }
 private fun refreshState() {
  if (!::host.isInitialized) return
  val service = CompanionService.instance; val state = service?.uiState ?: ConnectionState.disconnected; val ready = service?.connection?.ready == true
  val transfer = service?.transferState ?: TransferState()
  val authenticationPending=service?.features?.authentication?.requestId()!=null
  authenticationSetup.title.text=if (authenticationPending) "Review fingerprint request" else "Set up fingerprint approval"
  ui.describe(authenticationSetup,if (authenticationPending) "A laptop request is waiting. Review it before approving." else "Create and export your credential for laptop enrollment.")
  connectionTitle.text = state.title; connectionDetail.text = state.detail
  val network = service?.networkPrivacy?.current()
  networkPrivacyRow.title.text = when (network?.privacy) { "public" -> "Public/shared Wi-Fi"; "private" -> "Private Wi-Fi · confirmed by you"; "unknown" -> "Review current Wi-Fi"; else -> "Wi-Fi is not connected" }
  ui.describe(networkPrivacyRow, network?.reason ?: "All Wi-Fi and Bluetooth device data is encrypted. No plaintext discovery broadcasts.")
  ui.describe(networkConnectionRow, service?.connection?.lanEndpointLabel?.let { "Connected to $it · TLS 1.3 verified" }
   ?: service?.connection?.lanError?.ifBlank { null } ?: "Wi-Fi reconnects when the computer’s current address is available. Bluetooth can remain connected.")
  connectionBadge.text = if (ready) "Encrypted · ${service.connection.transportLabel}" else "Local connection"
  connectionBadge.setTextColor(if (ready) ui.success else ui.muted); connectionBadge.background = ui.shape(if (ready) ui.successSoft else ui.sidebar, 20)
  val address = prefs.getString(if (prefs.getString("transport", "both") == "bluetooth") "bluetooth_address" else "host", "").orEmpty(); computerRow.title.text = address.ifBlank { "Add your computer" }
  ui.describe(computerRow, if (address.isBlank()) "Pair once. Stay connected locally." else if (ready) "Connected over ${service.connection.transportLabel}" else "${state.title} · Tap to review connection settings")
  connectButton.text = if (address.isBlank()) "Set up computer" else if (service != null) "Reconnect" else "Connect"
  connectButton.isEnabled = state.phase !in listOf(ConnectionPhase.CONNECTING, ConnectionPhase.PAIRING); disconnectButton.isEnabled = service != null
  connectedControls.forEach { (view, feature) ->
   val enabled = ready && service.features.modules[feature]?.capability()?.optString("status") in listOf("available", "user_action_required")
   view.isEnabled = enabled && (feature !in listOf("lock", "screen") || service.connection.desktopAvailable(feature)) && (feature != "files" || !transfer.busy); view.alpha = if (view.isEnabled) 1f else .45f
  }
  // Apply text queued by older companion versions automatically, including offline.
  pending.getString("clipboard", null)?.takeIf { prefs.getBoolean("enable.clipboard", true) }?.let { text ->
   if (service != null) service.features.clipboard.command("set", JSONObject().put("text", text))
   else { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Unity", text)); pending.edit().remove("clipboard").apply() }
  }
  ui.describe(receivedClipboard, when {
   !prefs.getBoolean("enable.clipboard", true) -> "Clipboard sync is disabled in Features."
   !ready -> "Connect to sync clipboard text automatically."
   else -> service.features.clipboard.capability().optString("reason")
  })
  receivedUrl.view.isEnabled = pending.contains("url"); receivedUrl.view.alpha = if (receivedUrl.view.isEnabled) 1f else .45f; ui.describe(receivedUrl, pending.getString("url", "No web page waiting.")!!)
  ui.describe(folderRow, if (prefs.contains("tree")) "A folder is selected. Tap to change it." else "Choose a folder for remote browsing.")
  val listener = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(this, PhoneNotifications::class.java))
  ui.describe(notificationPermission, if (listener) "Allowed in Android settings." else "Not allowed. Tap to review Android settings.")
  val notificationManager = getSystemService(NotificationManager::class.java)
  val receivedChannel = notificationManager.getNotificationChannel(ReceivedItems.CHANNEL)
  val canNotify = (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) && notificationManager.areNotificationsEnabled() && receivedChannel?.importance != NotificationManager.IMPORTANCE_NONE
  ui.describe(receivedNotificationPermission, if (canNotify) "Tap a received file or link to open it directly. Shared text can be opened or copied." else "Notifications are disabled. Tap to allow received item alerts.")
  ui.describe(smsPermission, if (granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.SEND_SMS) && granted(Manifest.permission.READ_PHONE_STATE)) "Permissions granted." else "Some permissions are missing. Tap to review.")
  ui.describe(photoPermission, if (photoGranted()) "Photo/video access granted. Enable file transfers to download originals." else "Not allowed. Choose which photos and videos may be shared.")
  ui.describe(dndPermission, if (getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) "Policy access allowed." else "Not allowed. Tap to review Android settings.")
  featureRows.forEach { (id, value) ->
   val (row, description) = value; val toggle = featureSwitches.getValue(id); val enabled = prefs.getBoolean("enable.$id", id == "clipboard" || id !in sensitive)
   if (toggle.isChecked != enabled) toggle.isChecked = enabled
   val capability = service?.features?.modules?.get(id)?.capability()
   val summary = when { !enabled -> null; id == "audio" && service?.features?.audio?.capturing() == true -> "Sending phone audio · approval required before capture"; id == "audio" && service?.features?.audio?.playing == true -> "Playing laptop audio"; id == "audio" && service?.features?.audio?.lastError?.isNotBlank() == true -> service.features.audio.lastError; capability != null && capability.optString("status") == "available" -> "Ready"; capability != null -> capability.optString("reason"); else -> permissionHint(id, listener) ?: "Ready to connect" }
   ui.describe(row, if (summary == null) description else "$description\n$summary")
  }
  val screen = service?.features?.screen
  screenStatus.title.text = when { screen?.active() == true -> if (screen.source() == "phone") "Sharing phone screen" else "Viewing computer screen"; screen?.lastError?.isNotBlank() == true -> "Screen sharing ended"; else -> "No screen share" }
  ui.describe(screenStatus, screen?.lastError?.ifBlank { null } ?: if (screen?.active() == true) "${screen.state.replaceFirstChar { it.uppercase() }} · Tap Stop screen sharing to end." else "Enable Screen sharing in Features, then connect.")
  if (resumed && !isFinishing) { screen?.presentPending(this); service?.features?.audio?.presentPending(this); reviewNetworkPrivacy() }
  transferRow.title.text = transfer.title
  ui.describe(transferRow, if (transfer.busy) "Keep the local connection available until the transfer finishes." else if (transfer.failed) "Check your connection and try again." else "Original files are sent only when you choose them.")
 }
 private fun desktopRequest(feature: String, action: String, data: JSONObject = JSONObject()) {
  val service = CompanionService.instance ?: error("Connect first")
  service.connection.request(feature, action, data) { ok, error -> if (!ok) message(error) else if (feature == "lock") message("Computer locked") }
 }
 private fun connect(skipBluetoothPermission: Boolean = false) {
  if ((transportMode == "lan" && host.text.isBlank()) || fingerprint.text.isBlank()) { select(Page.SETTINGS); if (transportMode == "lan" && host.text.isBlank()) host.requestFocus() else fingerprint.requestFocus(); message("Enter your verified TLS fingerprint and a Wi-Fi address or Bluetooth computer"); return }
  val pin = fingerprint.text.toString().lowercase().filterNot { it == ':' || it.isWhitespace() }
  require(pin.length == 64 && Protocol.unhex(pin).size == 32) { "Paste the verified 64-digit TLS fingerprint" }
  val bluetooth = transportMode == "bluetooth"
  val btAddress = bluetoothAddress.text.toString().trim().uppercase(java.util.Locale.ROOT)
  if (transportMode != "lan") {
   require(BluetoothSupport.validAddress(btAddress)) { "Choose your paired Bluetooth computer, or enter its address from Unity’s desktop Settings" }
   if (!skipBluetoothPermission && Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { resumeBluetoothConnect = true; requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BluetoothSupport.PERMISSION_REQUEST); return }
   if (bluetooth) {
    val adapter = BluetoothSupport.adapter(this) ?: error("This phone has no Bluetooth adapter")
    if (!adapter.isEnabled) { resumeBluetoothConnect = true; startActivityForResult(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), 22); return }
   }
  }
  val address = host.text.toString().trim(); require(bluetooth || (transportMode == "both" && address.isBlank()) || (address.isNotBlank() && address.length <= 253 && address.none { it.isWhitespace() || it.code < 32 })) { "Enter a valid computer address" }
  val number = if (bluetooth) port.text.toString().toIntOrNull()?.takeIf { it in 1024..65535 } ?: prefs.getInt("port", 17161) else port.text.toString().toIntOrNull(); require(number != null && number in 1024..65535) { "Port must be between 1024 and 65535" }
  val oldPin = prefs.getString("fingerprint", null)
  if (oldPin != null && oldPin != pin) ui.dialog().setTitle("Replace your trusted computer?")
   .setMessage("This TLS identity differs from the saved computer. Verify the full new fingerprint on your computer. Continuing removes the old phone pairing credential and requires approval again.")
   .setNegativeButton("Cancel", null).setPositiveButton("Replace and pair") { _, _ -> safe { Credentials(this).clear(); saveConnection(address, number, pin) } }.show()
  else saveConnection(address, number, pin)
 }
 private fun saveConnection(address: String, number: Int, pin: String) {
  check(prefs.edit().putString("host", address).putInt("port", number).putString("fingerprint", pin).putString("transport", transportMode).putString("bluetooth_address", bluetoothAddress.text.toString().trim().uppercase(java.util.Locale.ROOT)).commit()) { "Could not save connection settings" }
  if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS) && !prefs.getBoolean("notification_permission_requested", false)) requestNotificationPermission()
  startForegroundService(Intent(this, CompanionService::class.java).setAction("connect")); select(Page.OVERVIEW)
 }
 private fun requestNotificationPermission() {
  if (Build.VERSION.SDK_INT >= 33) {
   prefs.edit().putBoolean("notification_permission_requested", true).apply()
   requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
  }
 }
 private fun reviewNetworkPrivacy(force: Boolean = false) {
  if (!resumed || isFinishing || networkPrivacyDialog?.isShowing == true) return
  val privacy = CompanionService.instance?.networkPrivacy ?: return
  val current = (if (force) privacy.current() else privacy.shouldAsk()) ?: return
  privacy.defer(current.key)
  var selected = if (current.privacy == "private") 1 else 0
  networkPrivacyDialog = ui.dialog().setTitle("Is this Wi-Fi public or private?")
   .setSingleChoiceItems(arrayOf("Public/shared Wi-Fi · choose if unsure", "Private Wi-Fi I trust"), selected) { _, index -> selected = index }
   .setView(ui.label("This choice is saved for this Wi-Fi. Android requires Wi-Fi name access, shown as Location permission, to recognize it after reconnecting. Unity stores only a device-local fingerprint of the name. Encryption stays on for either choice.", 14f, secondary = true).apply { setPadding(ui.dp(24),ui.dp(12),ui.dp(24),ui.dp(12)) })
   .setPositiveButton("Save choice") { _, _ -> safe {
    privacy.choose(current.key, if (selected == 1) "private" else "public")
    if (privacy.current()?.remembered!=true && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION),48)
    else if(privacy.current()?.remembered!=true) message("Turn on Location in Android settings so Unity can read this Wi-Fi name and remember your choice.")
    CompanionService.instance?.uiChanged()
   } }.setNegativeButton("Not now", null).create().also { dialog ->
    dialog.setOnDismissListener { networkPrivacyDialog = null }; dialog.show()
   }
 }
 private fun refreshConnectionFields() {
  ui.describe(transportRow, when (transportMode) { "bluetooth" -> "Bluetooth only"; "lan" -> "Wi-Fi / LAN only"; else -> "Keep both connected · Bluetooth for controls, Wi-Fi / LAN for files and streams" })
  if (::host.isInitialized) { host.isEnabled = transportMode != "bluetooth"; port.isEnabled = transportMode != "bluetooth" }
  bluetoothAddress.isEnabled = transportMode != "lan"
 }
 private fun chooseBluetoothComputer() {
  if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { resumeBluetoothConnect = false; requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BluetoothSupport.PERMISSION_REQUEST); return }
  val adapter = BluetoothSupport.adapter(this) ?: error("This phone has no Bluetooth adapter")
  if (!adapter.isEnabled) { resumeBluetoothConnect = false; startActivityForResult(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), 22); return }
  val devices = adapter.bondedDevices.sortedBy { it.name ?: it.address }
  if (devices.isEmpty()) {
   ui.dialog().setTitle("Pair your computer first").setMessage("Pair the phone and computer in system Bluetooth settings. Return here to choose the computer, then verify Unity’s TLS fingerprint.")
    .setPositiveButton("Open Bluetooth settings") { _, _ -> settings(Settings.ACTION_BLUETOOTH_SETTINGS) }.setNegativeButton("Cancel", null).show(); return
  }
  ui.dialog().setTitle("Choose your computer").setItems(devices.map { "${it.name ?: "Bluetooth device"}\n${it.address}" }.toTypedArray()) { _, index ->
   if (transportMode != "bluetooth") transportMode = "both"
   bluetoothAddress.setText(devices[index].address); refreshConnectionFields()
  }.setNegativeButton("Cancel", null).show()
 }
 private fun forgetComputer() {
  ui.dialog(destructive = true).setTitle("Forget this computer?").setMessage("The connection will stop and this phone’s saved pairing credential will be removed. Remove the phone from Unity on the computer to revoke its saved trust there too.")
   .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ -> safe {
    stopService(Intent(this, CompanionService::class.java)); Credentials(this).clear(); prefs.edit().remove("host").remove("fingerprint").remove("port").remove("transport").remove("bluetooth_address").apply()
    transportMode = "both"; bluetoothAddress.setText(""); refreshConnectionFields()
    host.setText(""); fingerprint.setText(""); port.setText(String.format(java.util.Locale.ROOT, "%d", 17161)); refreshState()
   } }.show()
 }
 private fun appearance() {
  val options = arrayOf("Follow system", "Light", "Dark"); val values = arrayOf("system", "light", "dark")
  ui.dialog().setTitle("Appearance").setSingleChoiceItems(options, values.indexOf(prefs.getString("appearance", "system"))) { dialog, index -> prefs.edit().putString("appearance", values[index]).apply(); dialog.dismiss(); recreate() }.setNegativeButton("Cancel", null).show()
 }
 private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
 private fun permissionHint(id: String, listener: Boolean): String? = when {
  id in listOf("notifications", "notification_actions", "media") && !listener -> "Allow notification access"
  id == "notification_actions" && !prefs.getBoolean("enable.notifications", false) -> "Enable notification mirroring"
  id == "sms" && (!granted(Manifest.permission.READ_SMS) || !granted(Manifest.permission.SEND_SMS)) -> "Allow SMS permissions"
  id == "call_notifications" && !granted(Manifest.permission.READ_PHONE_STATE) -> "Allow phone permission"
  id == "call_control" && !granted(Manifest.permission.CALL_PHONE) -> "Allow phone permission"
  id in listOf("photos", "browsing") && !prefs.getBoolean("enable.files", false) -> "Enable file transfers"
  id == "photos" && !photoGranted() -> "Choose which photos and videos may be shared"
  id == "browsing" && !prefs.contains("tree") -> "Choose a shared folder"
  id == "ringer" && !getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted -> "Allow Do Not Disturb access"
  id == "remote_input" && UnityKeyboard.instance == null && UnityAccessibility.instance == null -> "Select Unity keyboard or enable remote touch access"
  id == "hotspot" -> "Phone approval required for each request"
  id == "samsung_routines" -> SamsungRoutines.notificationProblem(this) ?: "Create keyword routines on your phone"
  id == "phone_connectivity" && !BluetoothSupport.allowed(this) -> "Allow Nearby devices to report connected Bluetooth devices"
  else -> null
 }
 private fun photoGranted(): Boolean {
  return if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) ||
   (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
 }
 private fun photoPermissions() {
  val permissions = if (Build.VERSION.SDK_INT >= 33) mutableListOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
   else mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)
  if (Build.VERSION.SDK_INT >= 34) permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
  requestPermissions(permissions.toTypedArray(), 3)
 }
 private fun settings(action: String) { startActivity(Intent(action)) }
 private fun chooseFolder() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 10) }
 private fun pickFile() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 11) }
 private fun validUrl(value: String): Uri {
  val uri = Uri.parse(value); require(value.length <= 8192 && value.none { it.code < 32 } && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "Enter a valid http or https web address" }; return uri
 }
 private fun shareUrl(value: String) {
  validUrl(value); val service = CompanionService.instance ?: error("Connect first")
  check(service.features.modules.getValue("urls").capability().getString("status") == "available") { "Enable web page sharing" }
  service.event("urls", JSONObject().put("url", value)); message("Web page shared")
 }
 private fun handleIntent(intent: Intent?) {
  when (intent?.getStringExtra("phone_action")) {
   "authentication" -> {
    // Consume this one-shot route even when Android restores MainActivity.
    // Legacy unbound notifications must not silently select a newer request.
    val request=intent.getStringExtra("request").orEmpty();intent.removeExtra("phone_action")
    startActivity(PhoneAuthenticationActivity.reviewIntent(this,request))
   }
   "hotspot" -> safe { settings(Settings.ACTION_WIRELESS_SETTINGS) }
   "samsung_routines" -> { intent.removeExtra("phone_action"); select(Page.FEATURES); samsungRoutinesSetup() }
   "clipboard", "url", "files" -> select(Page.SHARING)
  }
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
  if (requestCode == 22) { safe { if (resumeBluetoothConnect) connect() else chooseBluetoothComputer() }; return }
  val uri = data?.data ?: return
  safe { if (requestCode == 10) { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); prefs.edit().putString("tree", uri.toString()).apply(); CompanionService.instance?.features?.publish(); refreshState(); message("Shared folder updated") } else if (requestCode == 11) sendFile(uri) }
 }
 override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
 override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
  super.onRequestPermissionsResult(code, permissions, results)
  if (code==48) {
   val remembered=CompanionService.instance?.networkPrivacy?.current()?.remembered==true
   message(if(remembered) "Wi-Fi choice saved" else "Wi-Fi name access is needed to remember this network. Your choice still applies to this connection.")
  }
  if (code == BluetoothSupport.PERMISSION_REQUEST) safe {
   if (BluetoothSupport.allowed(this)) { if (resumeBluetoothConnect) connect() else chooseBluetoothComputer() }
   else if (resumeBluetoothConnect && transportMode == "both") {
    connect(skipBluetoothPermission = true); message("Wi-Fi / LAN can connect. Allow Nearby devices to also connect Bluetooth.")
   } else message("Nearby devices permission is needed to connect over Bluetooth")
  }
  CompanionService.instance?.features?.publish(); refreshState()
 }
 override fun onSaveInstanceState(out: Bundle) {
  out.putString("transport", transportMode); out.putString("bluetooth_address", bluetoothAddress.text.toString()); out.putString("page", selected.name); listOf("host" to host, "port" to port, "fingerprint" to fingerprint, "url" to url, "excluded" to excluded).forEach { (key, field) -> out.putString(key, field.text.toString()) }; super.onSaveInstanceState(out)
 }
 override fun onStart() {
  super.onStart(); CompanionEvents.observe(stateChanged)
  prefs.registerOnSharedPreferenceChangeListener(preferencesChanged); pending.registerOnSharedPreferenceChangeListener(preferencesChanged); refreshState()
 }
 override fun onStop() {
  CompanionEvents.remove(stateChanged)
  prefs.unregisterOnSharedPreferenceChangeListener(preferencesChanged); pending.unregisterOnSharedPreferenceChangeListener(preferencesChanged); super.onStop()
 }
 override fun onPause() { resumed = false; super.onPause() }
 override fun onResume() { super.onResume(); resumed = true; CompanionService.instance?.features?.publish(); refreshState() }
 companion object { private val sensitive = setOf("notifications", "notification_actions", "sms", "call_notifications", "clipboard", "files", "photos", "browsing", "media", "audio", "remote_input", "lock", "screen", "authentication", "samsung_routines", "phone_connectivity") }
}
