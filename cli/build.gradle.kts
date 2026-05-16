plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // okhttp / coroutines / kotlinx-serialization come transitively via
    // :core's `api` configuration (those types are on :core's public
    // surface — RemoteClient.Builder takes them, JsonRpcResponse carries
    // them, notifications flow them).
    implementation(project(":core"))
}

application {
    mainClass.set("ru.sipaha.spkremote.cli.MainKt")
}
