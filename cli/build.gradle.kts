plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    // `:core` references `okhttp3.OkHttpClient.Builder` in a default
    // constructor parameter, so consumers see it on the API surface.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

application {
    mainClass.set("ru.sipaha.spkremote.cli.MainKt")
}
