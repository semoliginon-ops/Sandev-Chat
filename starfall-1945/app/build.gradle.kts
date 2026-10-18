plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.sandev.starfall"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.sandev.starfall1945"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-gemma-runtime"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes { debug { isMinifyEnabled = false }; release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.12.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
}
