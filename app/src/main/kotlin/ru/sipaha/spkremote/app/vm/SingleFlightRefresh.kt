package ru.sipaha.spkremote.app.vm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Shared single-flight refresh primitive used by [SessionListStore].
 * Cancels any in-flight refresh tracked by [jobHolder]
 * before launching a new one, applies the success / failure policy
 * common to both stores, and writes results onto [target].
 *
 *   - On success: writes [UiData.Loaded] and invokes [onSuccess] for
 *     downstream side-effects (e.g. cache write-through).
 *   - On failure:
 *       * If [target] is already [UiData.Loaded] (we have stale data the
 *         user can read), the error is surfaced via [emitError] and the
 *         Loaded state is preserved.
 *       * Otherwise the target is flipped to [UiData.Error].
 *
 * [CancellationException] is re-thrown to honour structured concurrency.
 *
 * Why a single-flight: rapid `refresh*` invocations from notifications
 * + user actions otherwise interleave and the *last to land* wins —
 * which on a slow link is often the *oldest* request.
 */
internal fun <T> singleFlightRefresh(
    scope: CoroutineScope,
    target: MutableStateFlow<UiData<T>>,
    jobHolder: () -> Job?,
    setJob: (Job?) -> Unit,
    emitError: (String) -> Unit,
    fetch: suspend () -> T,
    onSuccess: (T) -> Unit = {},
) {
    jobHolder()?.cancel()
    val newJob = scope.launch {
        runCatching { fetch() }
            .onSuccess { value ->
                target.value = UiData.Loaded(value)
                onSuccess(value)
            }
            .onFailure { err ->
                if (err is kotlinx.coroutines.CancellationException) throw err
                val message = err.message ?: "unknown error"
                if (target.value is UiData.Loaded) {
                    emitError(message)
                } else {
                    target.value = UiData.Error(message)
                }
            }
    }
    setJob(newJob)
}
