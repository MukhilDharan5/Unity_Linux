package org.unityconnect.companion

import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileTransferTest {
 private class Provider(val root: File) : ContentProvider() {
  val entries = linkedMapOf<Uri, ContentValues>()
  val published = mutableListOf<Uri>()
  var failedWrite = false
  var source: File? = null
  var opens = 0
  override fun onCreate() = true
  override fun getType(uri: Uri) = "application/octet-stream"
  override fun insert(uri: Uri, values: ContentValues?): Uri {
   val item = uri.buildUpon().appendPath((entries.size + 1).toString()).build()
   entries[item] = ContentValues(values!!)
   return item
  }
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
   entries.getValue(uri).putAll(values)
   if (values?.getAsInteger(MediaStore.Downloads.IS_PENDING) == 0) published.add(uri)
   return 1
  }
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
   entries.remove(uri); File(root, uri.lastPathSegment!!).delete(); return 1
  }
  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
   MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("one-shot.bin")) }
  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
   if (source != null) {
    opens++; check(opens == 1) { "Provider supports only one open" }
    return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
   }
   check(!failedWrite) { "Downloads storage unavailable" }
   assertEquals(1, entries.getValue(uri).getAsInteger(MediaStore.Downloads.IS_PENDING))
   return ParcelFileDescriptor.open(File(root, uri.lastPathSegment!!),
    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
  }
 }
 private fun register(service: CompanionService, authority: String, provider: Provider) {
  provider.attachInfo(service, ProviderInfo().apply { this.authority = authority })
  ShadowContentResolver.registerProviderInternal(authority, provider)
 }
 private fun digest(bytes: ByteArray) = Protocol.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
 private fun begin(id: String, name: String, bytes: ByteArray) = JSONObject().put("id", id).put("name", name)
  .put("size", bytes.size).put("sha256", digest(bytes))
 private fun chunk(id: String, bytes: ByteArray) = JSONObject().put("id", id).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))

 @Test fun receivedFilesArePublishedToDownloadsOnlyAfterIntegrityVerification() {
  val controller = Robolectric.buildService(CompanionService::class.java).create()
  val service = controller.get()
  val root = File(service.cacheDir, "download-test").apply { mkdirs() }
  val provider = Provider(root); register(service, "media", provider)
  try {
   val id = "ab".repeat(16); val bytes = ByteArray(100003) { (it % 251).toByte() }
   val feature = service.features.files
   feature.command("begin", begin(id, "தமிழ் space.unknown", bytes))
   for (part in bytes.toList().chunked(49152)) feature.command("chunk", chunk(id, part.toByteArray()))
   assertTrue(provider.entries.isEmpty())
   assertTrue(shadowOf(service.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
   val result = feature.command("end", JSONObject().put("id", id))!!
   assertEquals("received", result.getString("state"))
   assertEquals("Downloads/Unity", result.getString("location"))
   val uri = provider.published.single()
   val notice = shadowOf(service.getSystemService(NotificationManager::class.java)).allNotifications.single()
   assertEquals(uri, ReceivedItems.viewerIntent(shadowOf(notice.contentIntent).savedIntent).data)
   assertEquals("தமிழ் space.unknown", provider.entries.getValue(uri).getAsString(MediaStore.Downloads.DISPLAY_NAME))
   assertEquals("Download/Unity", provider.entries.getValue(uri).getAsString(MediaStore.Downloads.RELATIVE_PATH))
   assertEquals("application/octet-stream", provider.entries.getValue(uri).getAsString(MediaStore.Downloads.MIME_TYPE))
   assertArrayEquals(bytes, File(root, uri.lastPathSegment!!).readBytes())
   assertTrue(File(service.filesDir, "received").listFiles()!!.isEmpty())
   val bad = "cd".repeat(16)
   feature.command("begin", begin(bad, "bad.bin", byteArrayOf(1)))
   feature.command("chunk", chunk(bad, byteArrayOf(2)))
   assertThrows(IllegalStateException::class.java) { feature.command("end", JSONObject().put("id", bad)) }
   assertEquals(1, provider.published.size)
   assertEquals(1, shadowOf(service.getSystemService(NotificationManager::class.java)).allNotifications.size)
   // Empty files are valid, and a failed Downloads write is never acknowledged.
   val empty = "ef".repeat(16)
   feature.command("begin", begin(empty, "empty", byteArrayOf()))
   feature.command("end", JSONObject().put("id", empty))
   assertEquals(2, provider.published.size)
   provider.failedWrite = true
   val failed = "12".repeat(16)
   feature.command("begin", begin(failed, "failed.bin", byteArrayOf()))
   assertThrows(IllegalStateException::class.java) { feature.command("end", JSONObject().put("id", failed)) }
   assertEquals(2, provider.entries.size)
   assertTrue(File(service.filesDir, "received").listFiles()!!.isEmpty())
  } finally { controller.destroy(); root.deleteRecursively(); shadowOf(Looper.getMainLooper()).idle() }
 }

 @Test fun oneShotDocumentProviderSendsExactlyTheHashedSnapshot() {
  val controller = Robolectric.buildService(CompanionService::class.java).create()
  val service = controller.get()
  val bytes = ByteArray(100003) { (it % 241).toByte() }
  val source = File(service.cacheDir, "source").apply { writeBytes(bytes) }
  val provider = Provider(service.cacheDir).apply { this.source = source }
  register(service, "test.files", provider)
  try {
   service.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("enable.files", true).commit()
   val connected = TestConnection(service)
   connected.lan.messages.clear()
   service.features.files.send(Uri.parse("content://test.files/document"))
   assertEquals(1, provider.opens)
   val data = mutableListOf<JSONObject>()
   while (true) {
    val item = connected.lan.next { it.optString("feature") == "files" }.getJSONObject("data")
    data.add(item); if (item.getString("phase") == "end") break
   }
   assertEquals("begin", data.first().getString("phase"))
   assertEquals(digest(bytes), data.first().getString("sha256"))
   assertEquals("end", data.last().getString("phase"))
   val actual = data.filter { it.getString("phase") == "chunk" }.flatMap { Base64.decode(it.getString("data"), Base64.NO_WRAP).toList() }.toByteArray()
   assertArrayEquals(bytes, actual)
   assertFalse(service.cacheDir.listFiles()!!.any { it.name.startsWith("unity-send-") })
  } finally { controller.destroy(); source.delete(); shadowOf(Looper.getMainLooper()).idle() }
 }
}
