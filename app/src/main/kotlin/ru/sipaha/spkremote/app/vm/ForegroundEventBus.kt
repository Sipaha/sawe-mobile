package ru.sipaha.spkremote.app.vm

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide "the app just came back to the foreground" signal.
 *
 * Android does not invoke our WebSocket pump's lifecycle hooks when the
 * user backgrounds the app, so an `agent_session_state_changed`
 * notification fired by the server during the background window can be
 * silently dropped (or the OkHttp collector paused long enough that the
 * server gives up the notification frame). On every genuine
 * background-to-foreground transition we re-fetch the open session and
 * the open sessions list so the server is the source of truth for
 * "is the agent still Running?".
 *
 * See `docs/findings/2026-05-18-mobile-session-state-stuck-running.md`
 * on spk-editor main for the underlying hypothesis. This is the
 * mitigation path; a deeper fix in the WS pump's scope binding is
 * tracked separately.
 *
 * ### Why a singleton bus instead of [androidx.lifecycle.ProcessLifecycleOwner]
 *
 * `ProcessLifecycleOwner` lives in `androidx.lifecycle:lifecycle-process`,
 * which isn't currently on the dependency list (`libs.versions.toml` is
 * frozen). The same foreground-edge semantics are achievable with the
 * SDK-builtin [Application.ActivityLifecycleCallbacks] by counting
 * started activities — the `started` count goes `0 -> 1` exactly when
 * the user brings the (single-Activity) app to the foreground.
 *
 * ### First-cold-start discrimination
 *
 * The very first `0 -> 1` transition during process bring-up IS the
 * cold launch, where the existing `MainViewModel.coldStartLandingRoute()`
 * + `switchToServer` pipeline already triggers a full refresh as a side
 * effect of the initial connect. Emitting on that transition would
 * double-fetch and is wasteful (cheap, but pointless). The
 * [ForegroundEdgeDetector.hasReportedFirstStart] flag suppresses the
 * first emission and lets every subsequent foreground edge through.
 */
object ForegroundEventBus {
    /**
     * Replay = 0: a foreground event that fires while no collector is
     * attached is silently dropped. That's intentional — the collector
     * is `MainViewModel`, which is alive for the whole process lifetime
     * past the first Activity creation, so the only window where the
     * bus emits and no one listens is between [Application.onCreate]
     * (where we install the callbacks) and the moment Android
     * instantiates the ViewModel from `MainActivity.onCreate`. The very
     * first `0 -> 1` transition during that window is the cold-start
     * one, which we're suppressing anyway.
     */
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    private val detector = ForegroundEdgeDetector { _events.tryEmit(Unit) }

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
            override fun onActivityStarted(activity: Activity) {
                detector.onActivityStarted()
            }
            override fun onActivityStopped(activity: Activity) {
                detector.onActivityStopped()
            }
        })
    }
}

/**
 * Pure foreground-edge detector — extracted from [ForegroundEventBus] so
 * the `0 -> 1` started-count transition logic is testable without
 * spinning up an Android instrumentation harness.
 *
 * Counts started activities and invokes [onForeground] exactly when the
 * count goes `0 -> 1` AFTER the first start (the very first `0 -> 1`
 * during cold launch is suppressed — see [ForegroundEventBus] KDoc).
 *
 * Configuration-change Activity recreation interleaves
 * `onActivityStarted` for the new instance with `onActivityStopped` for
 * the old one (new starts before old stops), so the count never returns
 * to zero and no spurious foreground edge fires. This is the same
 * pattern [androidx.lifecycle.ProcessLifecycleOwner] uses internally.
 */
internal class ForegroundEdgeDetector(private val onForeground: () -> Unit) {
    @Volatile
    private var startedActivityCount: Int = 0

    @Volatile
    private var hasReportedFirstStart: Boolean = false

    fun onActivityStarted() {
        val previous = startedActivityCount
        startedActivityCount = previous + 1
        if (previous == 0) {
            if (!hasReportedFirstStart) {
                hasReportedFirstStart = true
                return
            }
            onForeground()
        }
    }

    fun onActivityStopped() {
        if (startedActivityCount > 0) {
            startedActivityCount -= 1
        }
    }
}
