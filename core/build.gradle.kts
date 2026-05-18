plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Promoted to `api` because their types leak onto :core's public surface
    // (RemoteClient takes OkHttpClient.Builder, returns JsonRpcResponse carrying
    // JsonElement, exposes SharedFlow<JsonElement> for notifications). Consumers
    // (:app, :cli) need these symbols transitively to bind ViewModels/UIs/CLI
    // output without re-declaring the deps themselves.
    api("com.squareup.okhttp3:okhttp:5.3.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Explicit kotlin-stdlib pin: OkHttp 5.x's transitive Kotlin runtime could
    // otherwise diverge from the compiler version. Propagated via `api` so
    // downstream `:app` / `:cli` see the same stdlib.
    api("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.0")
}

tasks.test {
    useJUnitPlatform {
        // Integration tests (those tagged "integration") require a live editor
        // and a SPK_EDITOR_PAIRING_URL env var. They are excluded from the
        // default :core:test run; opt in with -DincludeTags=integration.
        val include = System.getProperty("includeTags")
        if (include.isNullOrBlank()) {
            excludeTags("integration")
        } else {
            includeTags(*include.split(",").toTypedArray())
        }
    }
    testLogging {
        events("passed", "failed", "skipped")
    }
}
