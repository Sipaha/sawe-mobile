package ru.sipaha.spkremote.app.vm

import ru.sipaha.spkremote.core.PairingUrl
import ru.sipaha.spkremote.core.QueuedMessage
import ru.sipaha.spkremote.core.RemoteClient

/**
 * Typed callback contract between [ConnectionManager] and its owning
 * coordinator (today only [MainViewModel]). Replaces the six-lambda
 * constructor that [ConnectionManager] previously accepted — keeping
 * everything in one interface makes the lifecycle contract obvious at
 * a glance and removes the per-callback `Unit`-typed lambda parameters
 * that had ambiguous fire order (audit Fix T, light variant).
 *
 * ### Threading
 *
 * Each callback fires on whichever dispatcher [ConnectionManager]
 * happened to be on when it invoked it — typically Main for state-flow
 * collectors and IO for actions launched inside `withContext`. Callees
 * MUST NOT assume Main thread and MUST NOT perform long synchronous
 * work. See [ConnectionManager]'s top-level KDoc for the full contract.
 */
internal interface ConnectionLifecycle {
    /**
     * Called the moment a fresh [RemoteClient] is constructed for a new
     * active server (after the previous one has been torn down). Used
     * by the coordinator to refresh per-server UI caches.
     */
    fun onClientBound(url: PairingUrl, client: RemoteClient)

    /**
     * Called when the existing client is torn down (server removed,
     * switched away from, or reconfigured). Lets dependent stores reset
     * their per-server state flows.
     */
    fun onTearDown()

    /**
     * Called on every Disconnected → Connected edge so the coordinator
     * can re-fetch session lists, resume open sessions, etc.
     */
    fun onReconnected()

    /**
     * Called on every Connected → non-Connected falling edge. Distinct
     * from [onTearDown] which is for explicit server-switch / teardown:
     * this fires on transient drops the `RemoteClient` lifecycle loop
     * recovers from on its own (network blip, Wi-Fi flap, NAT timeout).
     *
     * The active client + observers stay alive; only flows that
     * actively *use* the wire (e.g. an in-flight chunked upload waiting
     * on an ack) need to react. Without this hook, those flows have to
     * detect the drop reactively via send-failure / ack-timeout, which
     * can sit for up to 30s before they pause — by which time the
     * subsequent [onReconnected]'s `resumeAll` has already run and
     * missed them (state still `Uploading`, not `Paused`).
     *
     * Default no-op so callers that don't care (e.g. tests stubbing the
     * lifecycle) can ignore it.
     */
    fun onConnectionInterrupted() {}

    /**
     * Routes expired queued messages back to the coordinator for
     * bounce-to-input recovery.
     */
    fun onMessageExpired(message: QueuedMessage)

    /**
     * Pre-tear-down hook that runs while the *previous* server id is
     * still active. Used to drop per-session state (open session,
     * subscriptions) so the new connect doesn't see stale flags.
     */
    fun onBeforeSwitch()

    /** Channel for user-facing error toasts/snackbars. */
    fun onError(message: String)
}
