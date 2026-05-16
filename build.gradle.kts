// Top-level build file. Plugins are configured per-module; declared here with
// `apply false` so versions stay aligned across `:core` and `:app`.
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.android.application") version "8.7.2" apply false
}
