package org.unityconnect.companion

import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object PinnedTls {
 fun context(fingerprint: String): SSLContext {
  val pin = Protocol.unhex(fingerprint)
  require(pin.size == 32) { "Enter the verified desktop fingerprint" }
  val trust = object : X509TrustManager {
   override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
   override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) { error("Not a server") }
   override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {
    require(chain.isNotEmpty()); chain[0].checkValidity()
    check(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(chain[0].encoded), pin)) {
     "Desktop TLS identity changed. Verify and pair again."
    }
   }
  }
  return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
 }
}
