import java.security.KeyStore
import java.security.MessageDigest

plugins { id("com.android.application") }

val previewKeystore = file(providers.environmentVariable("UNITY_ANDROID_PREVIEW_KEYSTORE")
 .getOrElse("${System.getProperty("user.home")}/.local/share/unity-build/signing/preview.keystore"))
val legacyKeystore = file(providers.environmentVariable("UNITY_ANDROID_KEYSTORE")
 .getOrElse("${System.getProperty("user.home")}/.android/debug.keystore"))
android {
 namespace = "org.unityconnect.companion"
 compileSdk = 37
 buildFeatures { resValues = true; aidl = true }
 defaultConfig {
  applicationId = "org.unityconnect.companion"
  minSdk = 29
  targetSdk = 35
  versionCode = 37
  versionName = "0.1.36"
  resValue("string", "app_name", "Unity")
  resValue("string", "peer_name_suffix", "")
  resValue("string", "keyboard_label", "Unity remote keyboard")
  resValue("string", "accessibility_label", "Unity remote touch")
  resValue("string", "admin_label", "Unity phone locking")
 }
 signingConfigs {
  getByName("debug") { storeFile = legacyKeystore }
  create("preview") {
   storeFile = previewKeystore
   storePassword = "android"
   keyAlias = "androiddebugkey"
   keyPassword = "android"
  }
 }
 buildTypes {
  create("preview") {
   initWith(getByName("debug"))
   applicationIdSuffix = ".preview"
   signingConfig = signingConfigs.getByName("preview")
   resValue("string", "app_name", "Unity Preview")
   resValue("string", "peer_name_suffix", " (Preview)")
   resValue("string", "keyboard_label", "Unity Preview remote keyboard")
   resValue("string", "accessibility_label", "Unity Preview remote touch")
   resValue("string", "admin_label", "Unity Preview phone locking")
   matchingFallbacks += "debug"
  }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 testOptions {
  unitTests.isReturnDefaultValues = true
  unitTests.isIncludeAndroidResources = true
 }
}

// Never silently generate a new identity for an APK advertised as an update.
for ((variant, keystore) in listOf("Debug" to legacyKeystore, "Preview" to previewKeystore)) {
 val verifySigning = tasks.register("verifyUnity${variant}Signing") {
  doLast {
   check(keystore.isFile) { "Missing Unity $variant signing key: $keystore. Restore the persistent key; see docs/build.md." }
   val store = KeyStore.getInstance(keystore, "android".toCharArray())
   val certificate = store.getCertificate("androiddebugkey")
   check(certificate != null && store.isKeyEntry("androiddebugkey")) { "Unity $variant keystore has no androiddebugkey private key" }
   val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
   val expected = rootProject.file("signing/${variant.lowercase()}.sha256").readText().trim()
   check(actual == expected) { "Unity $variant signing certificate mismatch. This key cannot update existing installations. Restore the original key; see docs/build.md." }
  }
 }
 tasks.configureEach {
  if (name == "validateSigning$variant") dependsOn(verifySigning)
 }
}
dependencies {
 implementation("dev.rikka.shizuku:api:13.1.5")
 implementation("dev.rikka.shizuku:provider:13.1.5")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.robolectric:robolectric:4.17")
}
