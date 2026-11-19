plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.chitour.familycast"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.chitour.familycast"
        minSdk = 26
        targetSdk = 34
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }
    flavorDimensions += "id"
    productFlavors {
        create("normal") {
            dimension = "id"
            manifestPlaceholders["appLabel"] = "عرض عائلي"
        }
        // نسخة ثانية تحمل الاسم الداخلي الذي ترسل إليه تطبيقات الأفلام زر البث
        create("wvc") {
            dimension = "id"
            applicationId = "com.instantbits.cast.webvideo"
            versionCode = 90_000_000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
            manifestPlaceholders["appLabel"] = "عرض عائلي ٢"
        }
    }
    signingConfigs {
        create("fixed") {
            storeFile = file("familycast.jks")
            storePassword = "familycast"
            keyAlias = "familycast"
            keyPassword = "familycast"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.4.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
