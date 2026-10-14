package org.unityconnect.companion

import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.graphics.Insets
import android.content.res.Configuration
import android.view.ViewGroup
import android.view.WindowInsets
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class ReceivedItemsTest {
 private val app get() = RuntimeEnvironment.getApplication()
 private fun notices() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
 @Test fun eachReceivedLinkHasAnImmutableOriginalTargetAndOpensTheBrowser() {
  val controller = Robolectric.buildService(CompanionService::class.java).create()
  val service = controller.get()
  try {
   service.features.modules.getValue("urls").command("open", JSONObject().put("url", "https://example.org/first"))
   val first = notices().single()
   service.features.modules.getValue("urls").command("open", JSONObject().put("url", "https://example.org/second"))
   assertEquals(2, notices().size)
   val intent = shadowOf(first.contentIntent).savedIntent
   assertEquals("https://example.org/first", intent.getStringExtra(ReceivedItems.VALUE))
   assertTrue(first.contentIntent.isImmutable)
   val activity = Robolectric.buildActivity(ReceivedItemActivity::class.java, intent).setup()
   val opened = shadowOf(activity.get()).nextStartedActivity
   assertEquals(Intent.ACTION_VIEW, opened.action); assertEquals("https://example.org/first", opened.dataString)
   assertTrue(activity.get().isFinishing); activity.destroy()
   val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(ReceivedItems.CHANNEL)
   assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
  } finally { controller.destroy() }
 }
 @Test fun fileNotificationsOpenTheExactUriWithMimeAndReadOnlyAccess() {
  val uri = Uri.parse("content://media/external_primary/downloads/41")
  ReceivedItems.file(app, uri, "received.pdf")
  val receipt = notices().single()
  val intent = shadowOf(receipt.contentIntent).savedIntent
  val view = ReceivedItems.viewerIntent(intent)
  assertEquals(uri, view.data); assertEquals("application/pdf", view.type)
  assertEquals(uri, view.clipData!!.getItemAt(0).uri)
  assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
  assertEquals(0, view.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
  assertEquals("Open file", receipt.actions.single().title.toString())
 }
 @Test fun unknownExtensionsKeepTheSavedFileAndOfferAnOpenAction() {
  ReceivedItems.file(app, Uri.parse("content://media/external_primary/downloads/42"), "தமிழ்.unknown")
  val intent = shadowOf(notices().single().contentIntent).savedIntent
  assertEquals("application/octet-stream", ReceivedItems.viewerIntent(intent).type)
  assertEquals("தமிழ்.unknown", intent.getStringExtra(ReceivedItems.NAME))
 }
 @Test fun receivedTextOpensAndCopiesItsOriginalSnapshotAfterAnotherShare() {
  ReceivedItems.text(app, "First shared text தமிழ்")
  val first = notices().single()
  ReceivedItems.text(app, "Later text")
  app.getSharedPreferences("settings", 0).edit().putString("appearance", "dark").commit()
  val open = Robolectric.buildActivity(ReceivedItemActivity::class.java, shadowOf(first.contentIntent).savedIntent).setup()
  assertFalse(open.get().isFinishing)
  assertEquals(Configuration.UI_MODE_NIGHT_YES, open.get().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
  val scroll = open.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
  scroll.dispatchApplyWindowInsets(WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(), Insets.of(4, 28, 5, 32)).build())
  assertEquals(28, scroll.paddingTop); assertEquals(32, scroll.paddingBottom)
  open.pause().stop().destroy()
  val copy = first.actions.first { it.title.toString() == "Copy text" }
  val activity = Robolectric.buildActivity(ReceivedItemActivity::class.java, shadowOf(copy.actionIntent).savedIntent).setup()
  shadowOf(Looper.getMainLooper()).idle()
  assertEquals("First shared text தமிழ்", app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
  assertTrue(activity.get().isFinishing); activity.destroy()
 }
 @Test fun rejectsNonWebLinksAndArbitraryFileUrisBeforeOpening() {
  for (url in listOf("file:///etc/passwd", "javascript:alert(1)", "https://example.org/\n")) {
   assertThrows(IllegalArgumentException::class.java) { ReceivedItems.link(app, url) }
  }
  val intent = Intent().putExtra(ReceivedItems.KIND, "file").putExtra(ReceivedItems.VALUE, "content://evil/arbitrary")
  assertThrows(IllegalArgumentException::class.java) { ReceivedItems.viewerIntent(intent) }
  assertTrue(notices().isEmpty())
 }
}
