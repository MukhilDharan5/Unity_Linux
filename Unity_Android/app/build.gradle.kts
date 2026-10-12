plugins { id("com.android.application") }
android {
 namespace = "org.unityconnect.companion"
 compileSdk = 37
 defaultConfig {
  applicationId = "org.unityconnect.companion"
  minSdk = 29
  targetSdk = 35
  versionCode = 3
  versionName = "0.1.2"
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 testOptions {
  unitTests.isReturnDefaultValues = true
  unitTests.isIncludeAndroidResources = true
 }
}
dependencies {
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.robolectric:robolectric:4.17")
}
