plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.sandev.starfalldungeon"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.sandev.starfalldungeon"
        minSdk = 33
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-local-gemma"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildTypes { debug { isMinifyEnabled = false }; release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}
dependencies {
    implementation(project(":llama-lib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
