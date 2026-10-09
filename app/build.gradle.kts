plugins {
    // Built-in Kotlin (AGP 9+): no org.jetbrains.kotlin.android plugin needed.
    id("com.android.application")
}

android {
    namespace = "com.oxipro.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oxipro.bridge"
        // Health Connect requires minSdk 26; BLE indications work fine here too.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Kotlin compiler options default jvmTarget to compileOptions.targetCompatibility
    // with built-in Kotlin, so no separate kotlinOptions{} block is needed.
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Health Connect Jetpack SDK
    implementation("androidx.health.connect:connect-client:1.1.0")

    // Coroutines for BLE callback -> suspend bridging
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
