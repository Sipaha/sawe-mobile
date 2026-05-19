package ru.sipaha.spkremote.app

import android.app.Application
import ru.sipaha.spkremote.app.diagnostics.CrashLogger
import ru.sipaha.spkremote.app.vm.ForegroundEventBus

/**
 * Custom [Application] subclass — registered as `android:name=".SpkApplication"`
 * in `AndroidManifest.xml`. Installs:
 *
 *  1. The uncaught-exception handler from [CrashLogger] before any
 *     other code runs, so crashes during Activity creation or ViewModel
 *     init are captured.
 *  2. The [ForegroundEventBus] activity-lifecycle callbacks, so every
 *     genuine background-to-foreground transition (skipping the cold
 *     launch one) is observable from `MainViewModel` and triggers a
 *     re-fetch of the open session + sessions list. Fixes the
 *     "session pill stuck at Running after resume" symptom — see
 *     `docs/findings/2026-05-18-mobile-session-state-stuck-running.md`
 *     on spk-editor main.
 *
 * Don't add Activity-level / ViewModel-level logic here — keep this
 * lean enough that adding it never introduces a new pre-onCreate crash
 * path the user can't diagnose.
 */
class SpkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        ForegroundEventBus.install(this)
    }
}
