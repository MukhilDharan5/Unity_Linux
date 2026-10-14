package org.unityconnect.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Credentials(context: Context) {
 private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
 val id: String @Synchronized get() = prefs.getString("id", null) ?: UUID.randomUUID().toString().replace("-", "").also { prefs.edit().putString("id", it).commit() }
 private fun key(): SecretKey {
  val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  (store.getKey("unity.device.credential", null) as? SecretKey)?.let { return it }
  val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
  generator.init(KeyGenParameterSpec.Builder("unity.device.credential", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
   .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  return generator.generateKey()
 }
 @Synchronized fun readSecret(): String? {
  val encoded = prefs.getString("credential", null) ?: return null
  val data = Base64.decode(encoded, Base64.NO_WRAP)
  require(data.size > 12)
  val cipher = Cipher.getInstance("AES/GCM/NoPadding")
  cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
  return String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
 }
 @Synchronized fun storeSecret(value: String) {
  require(Protocol.unhex(value).size == 32)
  val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
  val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
  check(prefs.edit().putString("credential", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
 }
 @Synchronized fun clear() { prefs.edit().remove("credential").commit() }
}
