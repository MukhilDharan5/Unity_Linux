package org.unityconnect.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Covers have a separate budget from gallery previews and small timeline updates. */
internal object MediaArtwork {
 const val MAX_EDGE = 1024
 const val MAX_BYTES = 192 * 1024
 fun encode(source: Bitmap): JSONObject {
  for (edge in listOf(1024, 768, 512, 320)) {
   val scale = minOf(1.0, edge.toDouble() / maxOf(source.width, source.height))
   val image = Bitmap.createScaledBitmap(source, maxOf(1, (source.width * scale).roundToInt()), maxOf(1, (source.height * scale).roundToInt()), true)
   try {
    for (quality in listOf(92, 88, 84, 80)) {
     val output = ByteArrayOutputStream(); check(image.compress(Bitmap.CompressFormat.JPEG, quality, output))
     val bytes = output.toByteArray()
     if (bytes.size <= MAX_BYTES) return JSONObject().put("mime", "image/jpeg").put("width", image.width).put("height", image.height)
      .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("id", hash(bytes)).put("preview", ImagePreview.encode(source, 128, 2048))
    }
   } finally { if (image !== source) image.recycle() }
  }
  error("Cover exceeds artwork budget")
 }
 private fun hash(bytes: ByteArray) = Protocol.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
 fun bytes(image: JSONObject, id: String? = null): ByteArray {
  require(image.getString("mime") == "image/jpeg")
  val encoded = image.getString("data"); require(encoded.length <= (MAX_BYTES + 2) / 3 * 4)
  val bytes = Base64.decode(encoded, Base64.NO_WRAP); require(bytes.size <= MAX_BYTES)
  require(bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[bytes.size - 2] == 0xff.toByte() && bytes.last() == 0xd9.toByte())
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  require(bounds.outWidth in 1..MAX_EDGE && bounds.outHeight in 1..MAX_EDGE && image.getInt("width") == bounds.outWidth && image.getInt("height") == bounds.outHeight)
  if (id != null) require(validId(id) && hash(bytes) == id)
  return bytes
 }
 fun decode(image: JSONObject): Bitmap? = runCatching { val bytes = bytes(image); BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
 fun validId(id: String) = id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }
 fun wire(player: JSONObject): JSONObject = JSONObject().apply {
  for (key in player.keys()) put(key, if (key=="artwork") player.optJSONObject(key)?.let { it.optJSONObject("preview") ?: it } ?: JSONObject.NULL else player.get(key))
 }
}
