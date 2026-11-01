package org.unityconnect.companion

import android.Manifest
import android.content.ContentUris
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Size
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Publish only a hash-verified file; failed writes never expose partial downloads. */
internal fun saveDownload(resolver: ContentResolver, file: File, name: String): Uri {
 require(Protocol.safeName(name)) { "Invalid file name" }
 val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
 val values = ContentValues().apply {
  put(MediaStore.Downloads.DISPLAY_NAME, name)
  put(MediaStore.Downloads.MIME_TYPE, mime)
  put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Unity")
  put(MediaStore.Downloads.IS_PENDING, 1)
 }
 val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
  ?: error("Could not create file in Downloads/Unity")
 try {
  resolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { it.copyTo(output, 49152) } }
   ?: error("Could not write file in Downloads/Unity")
  check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) { "Could not publish downloaded file" }
  return uri
 } catch (e: Exception) {
  runCatching { resolver.delete(uri, null, null) }
  throw e
 }
}

class FileFeature(service: CompanionService) : Module(service, "files") {
 private data class Incoming(val file: File, val stream: FileOutputStream, val name: String, val size: Long,
                             val digest: String, val hash: MessageDigest, var count: Long = 0)
 private val incoming = ConcurrentHashMap<String, Incoming>()
 @Synchronized override fun command(action: String, data: JSONObject): JSONObject? {
  val transfer = data.getString("id"); require(Protocol.validId(transfer))
  when (action) {
   "begin" -> {
    require(incoming.size < 4 && !incoming.containsKey(transfer))
    val name = data.getString("name"); require(Protocol.safeName(name))
    val size = data.getLong("size"); require(size in 0..Protocol.MAX_FILE)
    val digest = data.getString("sha256"); require(digest.length == 64 && Protocol.unhex(digest).size == 32)
    val root = File(service.filesDir, "received").apply { mkdirs() }
    val file = File(root, ".$transfer.part"); check(file.createNewFile()) { "Transfer already exists" }
    incoming[transfer] = Incoming(file, FileOutputStream(file), name, size, digest, MessageDigest.getInstance("SHA-256"))
   }
   "chunk" -> {
    val t = incoming[transfer] ?: error("Unknown transfer")
    val encoded = data.getString("data"); require(encoded.length <= 65536)
    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
    require(bytes.size <= 49152 && t.count + bytes.size <= t.size)
    t.stream.write(bytes); t.hash.update(bytes); t.count += bytes.size
   }
   "end" -> {
    val t = incoming.remove(transfer) ?: error("Unknown transfer")
    try {
     t.stream.fd.sync(); t.stream.close()
     check(t.count == t.size && Protocol.hex(t.hash.digest()) == t.digest) { "File integrity check failed" }
     val uri = saveDownload(service.contentResolver, t.file, t.name)
     ReceivedItems.file(service, uri, t.name)
     return JSONObject().put("state", "received").put("name", t.name).put("size", t.size).put("location", "Downloads/Unity")
    } finally { t.stream.close(); if (t.file.exists()) t.file.delete() }
   }
   "abort" -> { incoming.remove(transfer)?.let { it.stream.close(); it.file.delete() } }
   else -> error("Unsupported transfer action")
  }
  return null // A single operation result is emitted after verified completion.
 }
 @Synchronized fun abort(id: String) { incoming.remove(id)?.let { runCatching { it.stream.close(); it.file.delete() } } }
 @Synchronized fun abortAll() { incoming.values.forEach { runCatching { it.stream.close(); it.file.delete() } }; incoming.clear() }
 fun send(uri: Uri, transferId: String? = null): JSONObject {
  check(capability().getString("status") == "available") { "Enable file sharing on phone" }
  val resolver = service.contentResolver
  var name = uri.lastPathSegment?.substringAfterLast('/') ?: "shared-file"
  resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
  require(Protocol.safeName(name)) { "Rename this file before sending" }
  val snapshot = File.createTempFile("unity-send-", ".part", service.cacheDir)
  val transfer = transferId ?: UUID.randomUUID().toString().replace("-", "")
  require(Protocol.validId(transfer))
  var began = false
  var transferSender: ((JSONObject) -> Unit)? = null
  try {
   // Document providers may return a one-shot stream or change between opens.
   // Hash and send the same bounded local snapshot, opening the URI only once.
   var size = 0L; val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(49152)
   resolver.openInputStream(uri)?.use { input -> snapshot.outputStream().use { output ->
    while (true) { val n = input.read(buffer); if (n < 0) break; size += n
     require(size <= Protocol.MAX_FILE) { "File exceeds the 64 MiB limit" }
     output.write(buffer, 0, n); hash.update(buffer, 0, n)
    }
   } } ?: error("File unavailable")
   val send = service.connection.fileSender(transferId)
   transferSender = send
   send(JSONObject().put("phase", "begin").put("id", transfer).put("name", name).put("size", size).put("sha256", Protocol.hex(hash.digest())))
   began = true
   snapshot.inputStream().use { input -> while (true) { val n = input.read(buffer); if (n < 0) break
    send(JSONObject().put("phase", "chunk").put("id", transfer).put("data", Base64.encodeToString(buffer.copyOf(n), Base64.NO_WRAP)))
   } }
   send(JSONObject().put("phase", "end").put("id", transfer))
   return JSONObject().put("state", "transferred").put("transfer_id", transfer)
  } catch (e: Exception) {
   if (began) runCatching { transferSender?.invoke(JSONObject().put("phase", "abort").put("id", transfer)) }
   throw e
  } finally { snapshot.delete() }
 }
}
class PhotoFeature(service: CompanionService) : Module(service, "photos") {
 private val visible = ConcurrentHashMap<String, Uri>()
 private fun allowed(video: Boolean): Boolean {
  val permission = if (Build.VERSION.SDK_INT >= 33) {
   if (video) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES
  } else Manifest.permission.READ_EXTERNAL_STORAGE
  return granted(permission) || (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
 }
 override fun capability() = status((allowed(false) || allowed(true)) && service.getSharedPreferences("settings", 0).getBoolean("enable.files", false),
  "Grant photo/video access and enable file transfer on phone")
 override fun command(action: String, data: JSONObject): JSONObject {
  when (action) {
   "list" -> {
    val candidates = mutableListOf<Pair<JSONObject, Uri>>()
    for ((video, collection) in listOf(false to MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true to MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
     if (!allowed(video)) continue
     val columns = mutableListOf("_id", "_display_name", "_size", "date_added", "width", "height", "mime_type")
     if (video) columns.add("duration")
     try { service.contentResolver.query(collection, columns.toTypedArray(), null, null, "date_added DESC")?.use { c ->
      var count = 0
      while (c.moveToNext() && count++ < 60) {
       val uri = ContentUris.withAppendedId(collection, c.getLong(0))
       val item = JSONObject().put("name", Protocol.text(c.getString(1) ?: if (video) "Video" else "Photo", 255))
        .put("size", c.getLong(2).coerceAtLeast(0)).put("timestamp", c.getLong(3) * 1000)
        .put("width", c.getInt(4)).put("height", c.getInt(5)).put("kind", if (video) "video" else "image")
        .put("mime", c.getString(6) ?: if (video) "video/mp4" else "image/jpeg")
       if (video) item.put("duration", c.getLong(7).coerceAtLeast(0))
       candidates.add(item to uri)
      }
     } } catch (_: SecurityException) { /* A per-type permission may have just changed. */ }
    }
    val items = JSONArray(); visible.clear()
    for ((item, uri) in candidates.sortedByDescending { it.first.optLong("timestamp") }.take(60)) {
     val token = UUID.randomUUID().toString().replace("-", "")
     visible[token] = uri; items.put(item.put("id", token))
    }
    return JSONObject().put("items", items).put("preview_supported", true).put("video_supported", true).put("clipboard_supported", true)
   }
   "preview" -> {
    val token = data.getString("id"); val edge = data.optInt("edge", 256)
    require(Protocol.validId(token) && edge in 64..320)
    val uri = visible[token] ?: error("Gallery selection expired; refresh the list")
    val thumbnail = service.contentResolver.loadThumbnail(uri, Size(edge, edge), null)
    try { return JSONObject().put("id", token).put("preview", ImagePreview.encode(thumbnail, edge)) }
    finally { thumbnail.recycle() }
   }
   "get" -> return service.features.files.send(visible[data.getString("id")] ?: error("Gallery selection expired; refresh the list"),
    data.optString("transfer_id").ifEmpty { null })
   else -> error("Unsupported action")
  }
 }
}
class BrowseFeature(service: CompanionService) : Module(service, "browsing") {
 private val visible = ConcurrentHashMap<String, Uri>()
 private fun root(): Uri? = service.getSharedPreferences("settings", 0).getString("tree", null)?.let(Uri::parse)
 override fun capability() = status(root() != null && service.getSharedPreferences("settings", 0).getBoolean("enable.files", false), "Choose a folder with Android’s document picker and enable file transfer")
 override fun command(action: String, data: JSONObject): JSONObject {
  val tree = root() ?: error("No shared folder")
  val token = data.getString("id")
  val uri = if (token.isEmpty()) DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)) else visible[token] ?: error("Folder selection expired")
  when (action) {
   "get" -> return service.features.files.send(uri)
   "list" -> {
    val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(uri))
    val entries = JSONArray()
    service.contentResolver.query(childUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
       DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
     while (c.moveToNext() && entries.length() < 100) {
      val id = UUID.randomUUID().toString().replace("-", "")
      val child = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
      check(DocumentsContract.isChildDocument(service.contentResolver, uri, child)) { "Document provider escaped shared folder" }
      if (visible.size >= 512) visible.clear()
      visible[id] = child
      entries.put(JSONObject().put("id", id).put("name", (c.getString(1) ?: "").take(255)).put("directory", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR).put("size", c.getLong(3)))
     }
    }
    return JSONObject().put("entries", entries)
   }
   else -> error("Unsupported action")
  }
 }
}
