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
        versionCode = (project.findProperty("vc") as String?)?.toIntOrNull() ?: 1   // номер сборки задаёт GitHub
        versionName = "0.00"
    }
    // Постоянный ключ подписи: без него обновление «поверх» невозможно (каждая сборка была бы с новым ключом)
    signingConfigs {
        create("fixed") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("io.coil-kt:coil:2.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
