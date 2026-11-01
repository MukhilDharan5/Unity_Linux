package org.unityconnect.companion

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import javax.net.ssl.SSLSocket
import org.json.JSONArray
import org.json.JSONObject

/** Compiled with the unmodified Android Protocol.kt and PinnedTls.kt. No Android SDK stubs. */
fun main(args: Array<String>) {
 val (port, fingerprint, id, secret) = args
 val socket = PinnedTls.context(fingerprint).socketFactory.createSocket() as SSLSocket
 socket.enabledProtocols = arrayOf("TLSv1.3")
 socket.soTimeout = 15000
 socket.connect(InetSocketAddress("127.0.0.1", port.toInt()), 5000)
 socket.use {
  socket.startHandshake()
  val input = DataInputStream(socket.inputStream); val output = DataOutputStream(socket.outputStream)
  val caps = JSONArray(listOf("battery", "find", "files").map { JSONObject().put("id", it).put("status", "available").put("reason", "") })
  Protocol.write(output, JSONObject().put("type", "hello").put("version", 1).put("device_id", id).put("name", "Android Kotlin 📱").put("capabilities", caps))
  val response = Protocol.read(input)
  if (secret == "pair") {
   check(response.getString("type") == "pair_pending")
   val paired = Protocol.read(input); check(paired.getString("type") == "pair_result")
   println("PAIRED:" + paired.getString("secret")); return
  }
  check(response.getString("type") == "challenge")
  Protocol.write(output, JSONObject().put("type", "proof").put("mac", Protocol.proof(secret, id, response.getString("nonce"))))
  check(Protocol.read(input).getString("type") == "ready")
  check(Protocol.read(input).getString("type") == "capabilities")
  Protocol.write(output, JSONObject().put("type", "event").put("feature", "battery").put("data", JSONObject().put("level", 88).put("charging", false)))
  val content = "Android → Windows 📱".toByteArray(Charsets.UTF_8)
  val transfer = "1234567890abcdef1234567890abcdef"
  fun fileEvent(data: JSONObject) = Protocol.write(output, JSONObject().put("type", "event").put("feature", "files").put("data", data.put("id", transfer)))
  fileEvent(JSONObject().put("phase", "begin").put("name", "android.txt").put("size", content.size).put("sha256", Protocol.hex(MessageDigest.getInstance("SHA-256").digest(content))))
  fileEvent(JSONObject().put("phase", "chunk").put("data", java.util.Base64.getEncoder().encodeToString(content)))
  fileEvent(JSONObject().put("phase", "end"))
  val received = java.io.ByteArrayOutputStream(); var expectedHash = ""; var expectedSize = 0L
  while (true) {
   val message = Protocol.read(input)
   if (message.getString("type") == "ping") { Protocol.write(output, JSONObject().put("type", "pong")); continue }
   check(message.getString("type") == "command")
   val op = message.getString("id"); check(Protocol.validId(op))
   fun result(data: JSONObject) = Protocol.write(output, JSONObject().put("type", "result").put("id", op).put("ok", true).put("data", data).put("error", ""))
   when (message.getString("feature")) {
    "find" -> { check(message.getString("action") == "ring"); result(JSONObject().put("state", "ringing")) }
    "files" -> {
     val data = message.getJSONObject("data")
     when (message.getString("action")) {
      "begin" -> { check(Protocol.safeName(data.getString("name"))); expectedHash = data.getString("sha256"); expectedSize = data.getLong("size") }
      "chunk" -> { val chunk = java.util.Base64.getDecoder().decode(data.getString("data")); check(chunk.size <= 49152); received.write(chunk) }
      "end" -> {
       val bytes = received.toByteArray(); check(bytes.size.toLong() == expectedSize && Protocol.hex(MessageDigest.getInstance("SHA-256").digest(bytes)) == expectedHash)
       check(String(bytes, Charsets.UTF_8).startsWith("Windows → Android 📱"))
       result(JSONObject().put("state", "received").put("location", "Downloads/Unity")); println("INTEROP_OK"); return
      }
      else -> error("Unexpected file action")
     }
    }
    else -> error("Unexpected feature")
   }
  }
 }
}
