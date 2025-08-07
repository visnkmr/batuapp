plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
}

android {
    namespace = "apps.visnkmr.batu"
    compileSdk = 34

    defaultConfig {
        applicationId = "apps.visnkmr.batu"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // TEMP: disable code/resource shrinking to verify the Kotlin ICE isn't caused by R8/proguard
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        // Update to latest stable Compose Compiler
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    // Align Kotlin/Javac targets to avoid kapt mismatch
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Update to latest stable Compose BOM (2025 Q1)
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    // Preview/tooling only for debug
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Lifecycle + coroutines
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Security crypto for encrypted prefs (latest stable)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.security:security-crypto-ktx:1.1.0-alpha06")

    // OkHttp + logging
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // JSON
    implementation("org.json:json:20240303")

    // Ensure Kotlin compiler classpath has JetBrains annotations available (needed by kapt/IR)
    // Keep javax.annotation exclusion only
    configurations.all {
        exclude(group = "javax.annotation", module = "javax.annotation-api")
        resolutionStrategy {
            // Align all org.jetbrains:annotations usages to 23.0.0 to satisfy constraints
            force("org.jetbrains:annotations:23.0.0")
        }
    }

    // Keep annotations available at compile; match forced version
    compileOnly("org.jetbrains:annotations:23.0.0")
    kapt("org.jetbrains:annotations:23.0.0")
}
