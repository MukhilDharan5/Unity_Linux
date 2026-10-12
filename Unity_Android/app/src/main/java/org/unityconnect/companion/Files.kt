package org.unityconnect.companion

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
     val destination = File(t.file.parentFile, "$transfer-${t.name}")
     check(!destination.exists() && t.file.renameTo(destination))
     return JSONObject().put("state", "received").put("name", t.name).put("size", t.size)
    } finally { t.stream.close(); if (t.file.exists()) t.file.delete() }
   }
   "abort" -> { incoming.remove(transfer)?.let { it.stream.close(); it.file.delete() } }
   else -> error("Unsupported transfer action")
  }
  return null // A single operation result is emitted after verified completion.
 }
 @Synchronized fun abortAll() { incoming.values.forEach { runCatching { it.stream.close(); it.file.delete() } }; incoming.clear() }
 fun send(uri: Uri): JSONObject {
  check(capability().getString("status") == "available") { "Enable file sharing on phone" }
  val resolver = service.contentResolver
  var name = "shared-file"
  resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
  require(Protocol.safeName(name))
  var size = 0L; val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(49152)
  resolver.openInputStream(uri)?.use { input -> while (true) { val n = input.read(buffer); if (n < 0) break; size += n; require(size <= Protocol.MAX_FILE); hash.update(buffer, 0, n) } } ?: error("File unavailable")
  val transfer = UUID.randomUUID().toString().replace("-", "")
  service.event(id, JSONObject().put("phase", "begin").put("id", transfer).put("name", name).put("size", size).put("sha256", Protocol.hex(hash.digest())), true)
  resolver.openInputStream(uri)?.use { input -> while (true) { val n = input.read(buffer); if (n < 0) break;
   service.event(id, JSONObject().put("phase", "chunk").put("id", transfer).put("data", Base64.encodeToString(buffer.copyOf(n), Base64.NO_WRAP)), true)
  } } ?: error("File unavailable")
  service.event(id, JSONObject().put("phase", "end").put("id", transfer), true)
  return JSONObject().put("state", "transferred").put("transfer_id", transfer)
 }
}
class PhotoFeature(service: CompanionService) : Module(service, "photos") {
 private val visible = ConcurrentHashMap<String, Uri>()
 override fun capability(): JSONObject {
  val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
  val allowed = granted(permission) || (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
  return status(allowed && service.getSharedPreferences("settings", 0).getBoolean("enable.files", false), "Grant selected photo access and enable file transfer on phone")
 }
 override fun command(action: String, data: JSONObject): JSONObject {
  when (action) {
   "list" -> {
    val items = JSONArray(); visible.clear()
    service.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
      arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE,
       MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT),
      null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
     while (c.moveToNext() && items.length() < 30) {
      val token = UUID.randomUUID().toString().replace("-", "")
      visible[token] = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
      items.put(JSONObject().put("id", token).put("name", Protocol.text(c.getString(1) ?: "Photo", 255)).put("size", c.getLong(2))
       .put("timestamp", c.getLong(3) * 1000).put("width", c.getInt(4)).put("height", c.getInt(5)))
     }
    }
    return JSONObject().put("items", items).put("preview_supported", true)
   }
   "preview" -> {
    val token = data.getString("id"); val edge = data.optInt("edge", 256)
    require(Protocol.validId(token) && edge in 64..320)
    val uri = visible[token] ?: error("Photo selection expired; refresh the list")
    val thumbnail = service.contentResolver.loadThumbnail(uri, Size(edge, edge), null)
    try { return JSONObject().put("id", token).put("preview", ImagePreview.encode(thumbnail, edge)) }
    finally { thumbnail.recycle() }
   }
   "get" -> return service.features.files.send(visible[data.getString("id")] ?: error("Photo selection expired; refresh the list"))
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
