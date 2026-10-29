plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.matchclip.android"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.bohscam.android"
  minSdk = 29
  targetSdk = 35
  versionCode = 2
  versionName = "1.1"
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}