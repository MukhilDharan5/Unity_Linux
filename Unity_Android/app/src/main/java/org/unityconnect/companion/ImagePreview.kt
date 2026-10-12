package org.unityconnect.companion

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

object ImagePreview {
 const val MAX_BYTES = 32 * 1024
 fun fitSize(width: Int, height: Int, edge: Int): Pair<Int, Int> {
  require(width > 0 && height > 0 && edge in 64..320)
  val scale = minOf(1.0, edge.toDouble() / maxOf(width, height))
  return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
 }
 fun encode(source: Bitmap, edge: Int = 256): JSONObject {
  val (width, height) = fitSize(source.width, source.height, edge)
  val scaled = Bitmap.createScaledBitmap(source, width, height, true)
  try {
   for (quality in listOf(80, 65, 50, 35, 20)) {
    val output = ByteArrayOutputStream()
    check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, output))
    val bytes = output.toByteArray()
    if (bytes.size <= MAX_BYTES) return JSONObject().put("mime", "image/jpeg").put("width", width)
     .put("height", height).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
   }
   error("Image could not fit preview budget")
  } finally { if (scaled !== source) scaled.recycle() }
 }
}
