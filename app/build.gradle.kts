plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.cartracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.cartracker"
        minSdk = 26
        targetSdk = 28   // 对齐车机 Android 9,避开新版本的后台限制
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
