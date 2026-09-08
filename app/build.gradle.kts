// `java` on its own would resolve to the source-set accessor inside the `android`
// block, so the timeout type is imported rather than fully qualified.
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    // Note: AGP 9.0+ ships built-in Kotlin support, so `kotlin("android")` must
    // NOT be applied — doing so makes plugin-apply fail.
    // R-6c-multi: needed by [PairedServer] which is JSON-serialised into
    // the encrypted paired-server list.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ru.sipaha.sawe.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.sipaha.sawe.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // The `androidTest` tree exists for exactly one reason: the thing no
        // JVM/Robolectric test can reach — the production
        // `AndroidKeysetManager` + `android-keystore://` keyset wiring, and
        // the legacy import running on top of it. It only fails on a device,
        // and it is load-bearing (a broken keyset path silently degrades
        // every store to "no persistence").
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        named("main") {
            java.srcDirs("src/main/kotlin")
        }
        named("test") {
            java.srcDirs("src/test/kotlin")
        }
        named("androidTest") {
            java.srcDirs("src/androidTest/kotlin")
        }
    }

    // Release keystore is wired via Gradle properties (or env vars) so dev
    // machines without a keystore can still produce an unsigned release APK
    // for R8 verification. See README.md § "Release APK".
    val storeFilePath: String? = providers.gradleProperty("SPK_RELEASE_STORE_FILE").orNull
        ?: System.getenv("SPK_RELEASE_STORE_FILE")

    signingConfigs {
        create("release") {
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("SPK_RELEASE_STORE_PASSWORD").orNull
                    ?: System.getenv("SPK_RELEASE_STORE_PASSWORD")
                keyAlias = providers.gradleProperty("SPK_RELEASE_KEY_ALIAS").orNull
                    ?: System.getenv("SPK_RELEASE_KEY_ALIAS")
                keyPassword = providers.gradleProperty("SPK_RELEASE_KEY_PASSWORD").orNull
                    ?: System.getenv("SPK_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Only attach the signing config if a keystore was configured.
            // Without this guard, AGP would attempt to sign with an empty
            // keystore and fail; with the guard, `assembleRelease` produces
            // `app-release-unsigned.apk` and R8 still runs end-to-end.
            if (storeFilePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    // When upgrading: `Modifier.onFirstVisible` was renamed to `onVisibilityChanged` in 2026.04+.
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // Tink, used DIRECTLY: `TinkEncryptedPrefs` is this app's own
    // SharedPreferences-over-Tink layer (AES256_SIV keys / AES256_GCM
    // values, keysets wrapped by an Android Keystore key of our own).
    implementation(libs.tink.android)
    // 4.3.0 is the final upstream release (project is no longer maintained).
    implementation(libs.zxing.android.embedded)
    // Markdown rendering for assistant bubbles. The library pins a Compose
    // runtime version under the hood; keep this in step with the Compose
    // BoM above when bumping.
    //
    // We do NOT pull in Coil here. The lib's ImageTransformer hook lets us
    // hand it pre-decoded Painters from EntrySummary.images, so async
    // network/disk loaders are unnecessary — every image we render is
    // already a base64 blob carried inline on the wire.
    // TODO verify androidx.compose.runtime aligns with BoM after AGP-9 plugin migration lands.
    implementation(libs.markdown.renderer)

    // JUnit 5 + kotlinx-coroutines-test for pure-JVM unit tests of the
    // `:app` ViewModel-side helpers (e.g. RpcDecoding). The bulk of
    // `:app` is Android-only; only test files of pure JVM classes belong
    // here.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Enables @RunWith(RobolectricTestRunner) under the JUnit 5 Platform
    // (testDebugUnitTest uses useJUnitPlatform(); without this engine JUnit 4
    // @RunWith tests are silently skipped).
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    // Debug, not test, and that is deliberate: this artifact is a manifest-only
    // AAR whose entire payload is an `<activity androidx.activity.ComponentActivity>`
    // entry, and only a variant-scoped dependency takes part in the manifest merge.
    // The merged debug manifest is what Robolectric reads under testDebugUnitTest, so
    // this is what lets `createComposeRule()` launch its host activity; declared as
    // `testImplementation` it lands on the classpath but never in any manifest. Debug
    // scope also keeps it out of the release build entirely.
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.roborazzi.core)

    // Instrumentation tests. JUnit 4 on purpose — the device runner is
    // `AndroidJUnitRunner`, which is a JUnit 4 runner; the
    // `useJUnitPlatform()` below applies to the JVM `Test` tasks only and
    // does not touch `connectedDebugAndroidTest`.
    //
    // Nothing else is declared here deliberately: `androidTestImplementation`
    // extends the tested variant's `implementation`, so Tink and
    // kotlinx-serialization are already on this classpath, and AGP compiles
    // this source set as a friend of the main one so the `internal` data
    // layer is visible without widening it.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// Device guard for the connected (instrumentation) test tasks.
//
// WHY this exists: handed no device, AGP's connected-test task logs a warning
// and SUCCEEDS. `./gradlew :app:connectedDebugAndroidTest` is therefore green
// both when the 11 instrumentation tests pass and when not one of them ran —
// the exact shape of the Roborazzi `Compare`-instead-of-`Verify` trap: a check
// that returns normally when its precondition is absent. And these are the only
// tests that can reach the production `AndroidKeysetManager` wiring and the
// legacy import running on top of it (see `defaultConfig` above), so the silent
// pass hides precisely the failures nothing else can catch.
//
// So: ask the SDK's own `adb` who is attached, and refuse to pretend. The work
// lives in `doFirst`, so it costs nothing at configuration time and no process
// is started for `testDebugUnitTest`, `assembleDebug` or `assembleRelease`.
// ---------------------------------------------------------------------------

// Captured at configuration time as plain values — nothing is executed here.
// `sdkComponents.sdkDirectory` is whatever AGP itself resolved; ANDROID_HOME is
// the fallback. No path is hardcoded. ANDROID_SERIAL is read through `providers`
// on purpose: `System.getenv` inside a task action returns the *daemon's*
// environment, which is not the environment the developer typed the command in.
val guardSdkDirFromAgp: Provider<Directory> = androidComponents.sdkComponents.sdkDirectory
val guardAndroidHome: Provider<String> = providers.environmentVariable("ANDROID_HOME")
val guardAndroidSerial: Provider<String> = providers.environmentVariable("ANDROID_SERIAL")

// Matched by name shape rather than one hardcoded string, so the aggregate
// `connectedAndroidTest` and any variant added later
// (`connectedStagingAndroidTest`, …) are guarded the day they appear. AGP's own
// task type would work too, but it lives in an `internal` package we would then
// be pinned to.
tasks.matching { it.name.matches(Regex("^connected[A-Za-z0-9]*AndroidTest$")) }.configureEach {
    doFirst {
        val agpSdkDir: File? = guardSdkDirFromAgp.orNull?.asFile
        val sdkDir: File = agpSdkDir?.takeIf { it.isDirectory }
            ?: guardAndroidHome.orNull?.let(::File)?.takeIf { it.isDirectory }
            ?: throw GradleException(
                "$name cannot find the Android SDK: neither AGP's sdkDirectory " +
                    "($agpSdkDir) nor ANDROID_HOME points at a directory, so there is no " +
                    "`adb` to ask whether a device is attached. Set ANDROID_HOME.",
            )
        val adb = sequenceOf("platform-tools/adb", "platform-tools/adb.exe")
            .map { File(sdkDir, it) }
            .firstOrNull { it.canExecute() }
            ?: throw GradleException(
                "$name found no executable `adb` under $sdkDir/platform-tools. Install the " +
                    "SDK platform-tools; without them this task cannot tell a real run from " +
                    "a run against nothing, which is the whole reason for this guard.",
            )

        val process = ProcessBuilder(adb.absolutePath, "devices")
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val rawOutput = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw GradleException(
                "`${adb.absolutePath} devices` did not answer within 60s. Refusing to start " +
                    "$name blind — a hung adb server is not evidence that a device is there.",
            )
        }

        // `adb devices` prints a header, optional `* daemon …` chatter, then
        // one `<serial>\t<state>` line per device. Only state `device` can run
        // anything: `offline` (an emulator still booting — the single most
        // likely way to land here), `unauthorized` (RSA prompt not accepted)
        // and `no permissions` all mean the suite would install nowhere.
        // Counting them would reproduce the very bug this guard fixes.
        val attached: List<Pair<String, String>> = rawOutput.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("*") &&
                    !line.startsWith("List of devices") &&
                    !line.startsWith("adb server")
            }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2) parts[0] to parts.drop(1).joinToString(" ") else null
            }
            .toList()
        val usable = attached.filter { it.second == "device" }

        // ANDROID_SERIAL is honoured strictly: it is what adb itself would
        // target, so "some other device is plugged in" is not a pass — it would
        // silently run the suite on the wrong phone.
        val wantedSerial = guardAndroidSerial.orNull?.trim().orEmpty()
        val selected =
            if (wantedSerial.isEmpty()) usable else usable.filter { it.first == wantedSerial }

        if (selected.isEmpty()) {
            val seen = if (attached.isEmpty()) {
                "  (none)"
            } else {
                attached.joinToString("\n") { (serial, state) -> "  $serial\t$state" }
            }
            val serialNote = if (wantedSerial.isEmpty()) {
                ""
            } else {
                "\nANDROID_SERIAL is set to `$wantedSerial`, and that serial is not attached in " +
                    "state `device`. It is obeyed strictly: another attached device does NOT " +
                    "satisfy it, because the suite would then run somewhere you did not ask for.\n"
            }
            throw GradleException(
                """
                |$name has no device to run on.
                |
                |The instrumentation tests need a real connected device or a BOOTED emulator.
                |Devices reported `offline` or `unauthorized` do not count — an emulator that is
                |still booting is the usual way to get here, and "found a device, ran nothing" is
                |exactly the bug this guard exists to prevent: without it AGP only logs a warning
                |and this task SUCCEEDS, so a run in which zero tests executed is indistinguishable
                |from a green one.
                |$serialNote
                |`${adb.absolutePath} devices` reported:
                |$seen
                |
                |Start the project's emulator, wait for it to finish booting, then re-run:
                |    ${'$'}ANDROID_HOME/emulator/emulator -avd saweEmu &
                |    ${'$'}ANDROID_HOME/platform-tools/adb wait-for-device
                |    ${'$'}ANDROID_HOME/platform-tools/adb shell getprop sys.boot_completed   # must print 1
                |
                |Or plug in a device with USB debugging enabled and accept the RSA prompt on it.
                """.trimMargin(),
            )
        }

        logger.lifecycle(
            "[connected-test guard] ${adb.absolutePath}: " +
                selected.joinToString(", ") { (serial, state) -> "$serial ($state)" },
        )
    }
}
