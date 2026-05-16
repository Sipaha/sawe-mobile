plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
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
