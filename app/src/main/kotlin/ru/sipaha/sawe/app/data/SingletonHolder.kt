package ru.sipaha.sawe.app.data

import android.content.Context

/**
 * The process-wide-singleton half of the repositories in this package,
 * factored out of the eight companion objects that used to spell it out
 * by hand.
 *
 * **Why the repositories are singletons at all.** Each one owns a
 * `by lazy` [android.content.SharedPreferences] handle — for the
 * encrypted stores that lazy costs a Tink keyset unwrap through the
 * Android Keystore, which is the single most expensive thing on the
 * cold-start path — and several own in-memory state that must not fork
 * (debounce timers, write-coalescing buffers, the prune clock). Two
 * instances would mean two of those, racing on the same file. The
 * per-repository reason it matters is documented on each repository; this
 * class only carries the mechanics.
 *
 * **Why the provider is rebound on every [get].** These repositories scope
 * their keys by the active server id, read through a `() -> String?`
 * provider rather than captured at construction, so `switchToServer` is
 * visible to the very next `save`/`load`. The provider handed in by the
 * *first* caller is a lambda closing over that caller's live state, so if
 * the second caller's lambda were dropped on the floor the singleton would
 * keep answering with the first ViewModel's notion of "active server" for
 * the life of the JVM — silently, and for good (audit Fix B). Rebinding
 * unconditionally is what makes a second `get` safe; doing it here means a
 * new store cannot forget it. See the KDoc on
 * [DraftRepository.activeServerProvider] for the full story.
 *
 * **Locking.** Create-and-rebind happens under this holder's monitor, so
 * two threads racing the first [get] cannot build two instances, and a
 * rebind is never interleaved with a construction. Nothing reads
 * [instance] outside that monitor, which is why the field is a plain
 * `var`: the `@Volatile` these companions used to carry guarded a
 * double-checked read that no call site ever performed.
 *
 * @param create builds the instance. Called at most once, already holding
 *   the monitor, with the **application** context — the holder applies
 *   [Context.getApplicationContext] itself so no call site can leak an
 *   Activity into a process-lifetime object.
 */
internal class SingletonHolder<T : Any>(private val create: (Context) -> T) {

    private var instance: T? = null

    /**
     * The process-wide instance, creating it on first call and applying
     * [rebind] on **every** call — see the class KDoc for why that is not
     * optional.
     */
    fun get(context: Context, rebind: (T) -> Unit = {}): T = synchronized(this) {
        val store = instance ?: create(context.applicationContext).also { instance = it }
        rebind(store)
        store
    }

    /**
     * Test hook — drop the instance so the next [get] builds a fresh one.
     * Process-wide state outlives a single test case; a suite that wants an
     * unwritten store, or wants to observe the create-once behaviour, has
     * to be able to get back to "never created".
     */
    fun resetForTest() {
        synchronized(this) { instance = null }
    }
}
