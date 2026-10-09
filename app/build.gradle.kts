plugins { id("com.android.application") }
dependencies { implementation("androidx.webkit:webkit:1.12.1") }
android {
    namespace = "org.flashconvert.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.flashconvert.mobile"
        minSdk = 29
        targetSdk = 32
        versionCode = 2
        versionName = "1.1"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
