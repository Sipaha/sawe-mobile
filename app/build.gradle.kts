plugins {
    alias(libs.plugins.android.application)
    // Note: AGP 9.0+ ships built-in Kotlin support, so `kotlin("android")` must
    // NOT be applied — doing so makes plugin-apply fail.
    // R-6c-multi: needed by [PairedServer] which is JSON-serialised into
    // the EncryptedSharedPreferences-backed paired-server list.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ru.sipaha.spkremote.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.sipaha.spkremote.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
    // Encrypted SharedPreferences for persisting the pairing URL. The
    // 1.1.0-alpha07 release is the final published alpha on Google's
    // Maven (security-crypto has been in alpha for ages; the stable
    // 1.0.0 branch depends on a deprecated Tink and breaks on AGP 8+).
    implementation(libs.androidx.security.crypto)
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
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.roborazzi.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
