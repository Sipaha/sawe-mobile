// Top-level build file. Plugins are configured per-module; declared here with
// `apply false` so versions stay aligned across `:core` and `:app`.
plugins {
    kotlin("jvm") version "2.3.21" apply false
    // Note: `kotlin("android")` is intentionally NOT declared. As of AGP 9.0+
    // Kotlin support is built into the Android Gradle Plugin and applying the
    // legacy `org.jetbrains.kotlin.android` plugin causes configuration to fail.
    kotlin("plugin.serialization") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("com.android.application") version "9.2.0" apply false
}
