// Top-level Gradle Kotlin DSL

plugins {
    id("com.android.application") version "8.4.2" apply false
    // Align with Compose Compiler 1.5.15 which requires Kotlin 1.9.25
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
}
