plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.deskclock.launcher"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.deskclock.launcher"
        minSdk = 24
        targetSdk = 28
        versionCode = 3
        versionName = "1.0.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}
