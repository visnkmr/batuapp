plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24"
}

android {
    // Ensure Android/Kotlin plugins are applied so androidx/android symbols resolve
    namespace = "apps.visnkmr.batu"
    compileSdk = 34

    defaultConfig {
        applicationId = "apps.visnkmr.batu"
        minSdk = 21
        targetSdk = 34
        versionCode = 6
        versionName = "1.01"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Exclude legacy Java sources from compilation (Compose-only build)
    applicationVariants.all {
        // no-op: placeholder to keep android block valid
    }
    tasks.withType<JavaCompile>().configureEach {
        // Exclude specific Java files from compilation
        exclude("io/github/visnkmr/tvcalendar/MainActivity.java")
        exclude("io/github/visnkmr/tvcalendar/RecyclerViewAdapter.java")
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // Create smaller APKs per ABI/density (use AAB or pick specific splits)
    splits {
        abi {
            // Build only a single universal APK (no per-ABI splits)
            isEnable = false
            isUniversalApk = true
        }
        density {
            // Disable density splits to keep a single common APK
            isEnable = false
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn"
        )
    }
    // Ensure Android dependencies are available at configuration time
    configurations.all {
        resolutionStrategy.force(
            "androidx.annotation:annotation:1.7.1"
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core + Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.1")
    implementation("androidx.compose.material:material-icons-extended")

    // Optional window size utils for responsive layouts
    implementation("androidx.compose.material3:material3-window-size-class")

    // Networking + Coroutines
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Security for encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // JSON (org.json is bundled with Android, but keep serialization plugin applied above if needed)

    // debug-only tooling
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
