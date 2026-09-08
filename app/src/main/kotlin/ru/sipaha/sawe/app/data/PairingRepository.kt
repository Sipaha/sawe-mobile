package ru.sipaha.sawe.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import ru.sipaha.sawe.core.PairingUrl
import java.util.UUID

/**
 * Persists the user's set of paired SPK Editor servers (R-6c-multi).
 *
 * **Multi-server (R-6c-multi):** the R-6b single-URL storage shape was
 * replaced with a list of [PairedServer] entries plus a separate
 * `active_server_id` pointer. Cold-start auto-resume now looks at
 * [activeServerId] (or the most-recently-connected entry) and reconnects
 * to that one; the new Servers screen lets the user switch between them.
 *
 * **Storage:** Android-Keystore-backed [TinkEncryptedPrefs] store
 * `spk_pairing` (same logical store as R-6b — only the keys changed. The
 * physical file moved once, when the deprecated
 * `EncryptedSharedPreferences` layer was replaced by [TinkEncryptedPrefs];
 * [LegacyPrefsImport] carried the old contents across on first launch, and
 * the reader it needed was deleted on 2026-09-08 once every install had run
 * it — see [LegacyPrefsFormat.NONE]).
 *
 *   - `paired_servers_v2` — JSON `List<PairedServer>` blob.
 *   - `active_server_id`  — String id of the active server, or absent.
 *
 * **R-6b → R-6c migration** ([migrateFromV1IfNeeded]):
 * on first read after the upgrade, if `paired_servers_v2` is missing
 * AND the legacy `pairing_url` key (R-6b) is present, parse the legacy
 * URL, build a single [PairedServer] from it, persist as v2, mark it
 * active, and delete the v1 key. One-shot — once v2 is present we never
 * look at v1 again.
 *
 * **Threading:** opening the file unwraps a Tink keyset through the
 * Android Keystore and blocks for tens to hundreds of ms (seconds on a
 * slow TEE). Cold start reaches [loadAll] / [activeServerId] from the
 * landing-route decision, so callers should [warmUp] — or use
 * [loadAllOnIo] / [activeServerIdOnIo] — instead of paying for it on
 * Main (N-56).
 *
 * **Failure path:** if [TinkEncryptedPrefs] is unavailable
 * (keystore unreachable — factory-reset credential store, device
 * migration), every method becomes a no-op equivalent: [loadAll]
 * returns the empty list, [activeServerId] returns `null`, mutations
 * silently drop. The app still works, just without persistence —
 * mirrors R-6b behavior. An *unreadable keyset* is recovered rather
 * than tolerated — see [EncryptedPrefs.open].
 */
class PairingRepository(private val context: Context) {

    private val prefs: SharedPreferences? by lazy {
        val opened = openPrefs()
        // Migration must run before any caller observes [loadAll] /
        // [activeServerId] for the first time. Doing it inside the
        // lazy-prefs initializer guarantees idempotency without an
        // explicit "init was called" flag.
        if (opened != null) migrateFromV1IfNeeded(opened)
        opened
    }

    // LegacyPrefsFormat.NONE: this store's pre-Tink file was an
    // `EncryptedSharedPreferences` one, and the only reader for that shape
    // went with the androidx.security:security-crypto dependency on
    // 2026-09-08 — there is nothing left for the importer to drain.
    private fun openPrefs(): SharedPreferences? =
        EncryptedPrefs.open(context, PREFS_NAME, LegacyPrefsFormat.NONE)

    /**
     * Resolve the encrypted prefs file (and run the v1→v2 migration) on
     * [Dispatchers.IO]. Idempotent — the `by lazy` delegate does the work
     * once, so every later synchronous read is a field access.
     */
    suspend fun warmUp() {
        withContext(Dispatchers.IO) { prefs }
    }

    /** [loadAll] off the main thread. */
    suspend fun loadAllOnIo(): List<PairedServer> = withContext(Dispatchers.IO) { loadAll() }

    /** [activeServerId] off the main thread. */
    suspend fun activeServerIdOnIo(): String? = withContext(Dispatchers.IO) { activeServerId() }

    /**
     * Migrate the R-6b single-URL `pairing_url` key into the R-6c
     * `paired_servers_v2` keyed-list shape. One-shot:
     *
     *   - If `paired_servers_v2` is already present → no-op.
     *   - If neither key is present (first-ever launch) → no-op.
     *   - Otherwise: parse the legacy URL, derive a label + fingerprint
     *     hex, generate a UUID, persist + mark active, delete the v1 key.
     *
     * Parse failure (corrupt v1 value) silently drops the v1 key — the
     * user lands on the QR screen on the next cold start, identical to
     * a never-paired phone. We do NOT preserve the corrupted blob: the
     * R-6b cold-start path already had this "if it doesn't parse, the
     * user types a new URL" exit, and keeping the bytes around just
     * surfaces a permanent error on every boot.
     */
    private fun migrateFromV1IfNeeded(prefs: SharedPreferences) {
        val v2Json = runCatching { prefs.getString(KEY_LIST_V2, null) }.getOrNull()
        if (v2Json != null) return
        val v1Url = runCatching { prefs.getString(KEY_URL_V1, null) }.getOrNull()
        if (v1Url.isNullOrBlank()) return
        val parsed = PairingUrl.parse(v1Url).getOrNull()
        if (parsed == null) {
            // Best-effort cleanup — drop the unparseable legacy entry
            // so we don't reattempt on every boot.
            runCatching { prefs.edit().remove(KEY_URL_V1).apply() }
            return
        }
        val server = PairedServer(
            id = UUID.randomUUID().toString(),
            pairingUrl = v1Url,
            label = "${parsed.host}:${parsed.port}",
            fingerprintHex = parsed.fingerprint.joinToString("") { "%02x".format(it) },
            firstPairedAtMs = System.currentTimeMillis(),
            lastConnectedAtMs = null,
        )
        val payload = runCatching {
            JSON.encodeToString(ListSerializer(PairedServer.serializer()), listOf(server))
        }.getOrNull() ?: return
        runCatching {
            prefs.edit()
                .putString(KEY_LIST_V2, payload)
                .putString(KEY_ACTIVE_ID, server.id)
                .remove(KEY_URL_V1)
                .apply()
        }.onFailure { Log.w(TAG, "v1→v2 migration write failed", it) }
    }

    /**
     * All paired servers, sorted with the most-recently-connected first
     * (nulls last on [PairedServer.lastConnectedAtMs]) and breaking ties
     * with [PairedServer.firstPairedAtMs] DESC.
     *
     * Empty list when no servers are paired or when the encrypted prefs
     * file is unreachable.
     */
    fun loadAll(): List<PairedServer> {
        val p = prefs ?: return emptyList()
        val raw = runCatching { p.getString(KEY_LIST_V2, null) }.getOrNull() ?: return emptyList()
        val list = runCatching {
            JSON.decodeFromString(ListSerializer(PairedServer.serializer()), raw)
        }.onFailure {
            Log.w(TAG, "paired_servers_v2 corrupt; resetting", it)
            runCatching { p.edit().remove(KEY_LIST_V2).apply() }
        }.getOrDefault(emptyList())
        return list.sortedWith(
            compareByDescending<PairedServer> { it.lastConnectedAtMs ?: Long.MIN_VALUE }
                .thenByDescending { it.firstPairedAtMs },
        )
    }

    /** Lookup by id, or `null` if not paired (or prefs unavailable). */
    fun get(serverId: String): PairedServer? =
        loadAll().firstOrNull { it.id == serverId }

    /**
     * Insert [server] or overwrite the existing entry with the same
     * [PairedServer.id]. Caller is responsible for generating UUIDs;
     * see [MainViewModel.addServer] for the production path.
     */
    fun upsert(server: PairedServer) {
        val current = loadAll().toMutableList()
        val idx = current.indexOfFirst { it.id == server.id }
        if (idx >= 0) current[idx] = server else current.add(server)
        writeList(current)
    }

    /**
     * Remove a paired server. If it was the [activeServerId], the
     * active pointer is also cleared — caller decides what to switch to
     * next (see [MainViewModel.removeServer]).
     */
    fun remove(serverId: String) {
        val p = prefs ?: return
        val current = loadAll().toMutableList()
        val removed = current.removeAll { it.id == serverId }
        if (!removed) return
        val edit = p.edit().putString(
            KEY_LIST_V2,
            runCatching {
                JSON.encodeToString(ListSerializer(PairedServer.serializer()), current)
            }.getOrDefault("[]"),
        )
        val active = runCatching { p.getString(KEY_ACTIVE_ID, null) }.getOrNull()
        if (active == serverId) edit.remove(KEY_ACTIVE_ID)
        runCatching { edit.apply() }
            .onFailure { Log.w(TAG, "remove() failed", it) }
    }

    /** Stamp the last-connected timestamp on [serverId]. No-op if unknown. */
    fun setLastConnected(serverId: String, nowMs: Long) {
        val current = loadAll().toMutableList()
        val idx = current.indexOfFirst { it.id == serverId }
        if (idx < 0) return
        current[idx] = current[idx].copy(lastConnectedAtMs = nowMs)
        writeList(current)
    }

    /** The user's last-selected active server, or `null` if unset. */
    fun activeServerId(): String? {
        val p = prefs ?: return null
        return runCatching { p.getString(KEY_ACTIVE_ID, null) }.getOrNull()
    }

    /**
     * Set (or clear) the active-server pointer. Persisted separately
     * from the server list so the user's last manual selection
     * survives across removal of OTHER servers.
     */
    fun setActive(serverId: String?) {
        val p = prefs ?: return
        runCatching {
            val edit = p.edit()
            if (serverId == null) edit.remove(KEY_ACTIVE_ID) else edit.putString(KEY_ACTIVE_ID, serverId)
            edit.apply()
        }.onFailure { Log.w(TAG, "setActive() failed", it) }
    }

    private fun writeList(list: List<PairedServer>) {
        val p = prefs ?: return
        val payload = runCatching {
            JSON.encodeToString(ListSerializer(PairedServer.serializer()), list)
        }.getOrNull() ?: return
        runCatching { p.edit().putString(KEY_LIST_V2, payload).apply() }
            .onFailure { Log.w(TAG, "writeList() failed", it) }
    }

    companion object {
        private const val TAG = "PairingRepository"

        /** Prefs-file name; also the [PersistenceHealth] identifier for this store. */
        internal const val PREFS_NAME = "spk_pairing"
        private const val KEY_URL_V1 = "url"
        private const val KEY_LIST_V2 = "paired_servers_v2"
        private const val KEY_ACTIVE_ID = "active_server_id"

        private val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val holder = SingletonHolder(::PairingRepository)

        /**
         * Process-wide instance. No provider to rebind: this repository is
         * the *source* of the active server id every other store scopes by,
         * so it has nothing to scope itself against.
         */
        fun get(context: Context): PairingRepository = holder.get(context)
    }
}
