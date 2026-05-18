package ru.sipaha.spkremote.app.vm

import ru.sipaha.spkremote.core.RemoteClient

/**
 * Shared context handed to [SolutionStore] and [SessionStore] so they
 * can reach the active [RemoteClient] without holding a hard reference
 * to it (the client is recreated on every [switchToServer]).
 *
 * Implemented by [MainViewModel] which forwards to [ConnectionManager].
 * Kept as a tiny surface area so the stores don't pull in pairing /
 * lifecycle concerns.
 */
internal interface ConnectionContext {
    /** Currently-bound [RemoteClient], or null when no server is active or the socket is being torn down. */
    fun activeClient(): RemoteClient?

    /** Human-readable explanation of why [activeClient] is null right now (or otherwise unusable). */
    fun notConnectedMessage(): String

    /** Emit a transient one-shot user-facing error onto the shared snackbar channel. */
    fun emitError(message: String)
}
