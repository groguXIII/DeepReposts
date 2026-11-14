plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.deepreposts"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.deepreposts"
        minSdk = 28          // Android 9
        targetSdk = 34       // работает и на Android 16
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("io.coil-kt:coil:2.7.0") }
