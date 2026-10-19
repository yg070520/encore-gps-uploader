plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.cartracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.cartracker"
        minSdk = 23
        targetSdk = 23   // 实验: <24 才能在 Manifest 里静态收 CONNECTIVITY_CHANGE 等隐式广播; <26 不受后台服务限制
        versionCode = 2
        versionName = "0.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
