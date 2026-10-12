package org.unityconnect.companion

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Protocol {
 const val MAX_FRAME = 512 * 1024
 const val MAX_FILE = 64L * 1024 * 1024
 fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
 fun unhex(s: String): ByteArray {
  require(s.length % 2 == 0 && s.all { it in "0123456789abcdef" }) { "Invalid hexadecimal value" }
  return s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
 }
 fun validId(s: String) = s.length == 32 && s.all { it in "0123456789abcdef" }
 fun proof(secret: String, id: String, nonce: String): String {
  val mac = Mac.getInstance("HmacSHA256")
  mac.init(SecretKeySpec(unhex(secret), "HmacSHA256"))
  return hex(mac.doFinal("unity-v1:$id:$nonce".toByteArray(Charsets.UTF_8)))
 }
 fun read(input: DataInputStream): JSONObject {
  val length = input.readInt()
  require(length in 1..MAX_FRAME) { "Invalid frame length" }
  val bytes = ByteArray(length)
  input.readFully(bytes)
  return JSONObject(String(bytes, Charsets.UTF_8))
 }
 fun write(output: DataOutputStream, value: JSONObject) {
  val bytes = value.toString().toByteArray(Charsets.UTF_8)
  require(bytes.size in 1..MAX_FRAME)
  output.writeInt(bytes.size); output.write(bytes); output.flush()
 }
 fun text(value: String, maxBytes: Int): String {
  val bytes = value.toByteArray(Charsets.UTF_8)
  if (bytes.size <= maxBytes) return value
  var end = maxBytes
  while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
  return String(bytes, 0, end, Charsets.UTF_8)
 }
 fun safeName(s: String): Boolean = s.isNotBlank() && s.toByteArray(Charsets.UTF_8).size <= 255 && s != "." && s != ".." && s.none { it == '/' || it == '\\' || it.code < 32 }
}
