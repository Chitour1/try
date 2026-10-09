plugins { id("com.android.application") }
android {
  namespace = "org.flashconvert.mobile"
  compileSdk = 35
  defaultConfig {
    applicationId = "org.flashconvert.nativeconverter"
    minSdk = 29
    targetSdk = 32
    versionCode = 1
    versionName = "2.0.0"
  }
  buildTypes { release { isMinifyEnabled = false } }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}