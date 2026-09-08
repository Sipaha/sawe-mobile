package ru.sipaha.sawe.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry in the multi-server paired-servers list owned by
 * [PairingRepository] (R-6c-multi).
 *
 * **Identity:** [id] is a UUID v4 generated once at first save and
 * preserved across the lifetime of this paired-server entry — even
 * if the user re-pairs the SAME server with a fresh QR code, we
 * regenerate the id only if the entry was first removed and re-added
 * (because by then the per-server scoped repositories — drafts,
 * queue, nav — have been wiped on remove).
 *
 * **Persistence:** the whole list is JSON-encoded into a single
 * `paired_servers_v2` key inside [PairingRepository]'s
 * [TinkEncryptedPrefs] file. We rewrite the whole blob on
 * every mutation; typical list size is 1-3 entries, so the
 * partial-update bookkeeping isn't worth the complexity.
 *
 * **Ordering** in [PairingRepository.loadAll]:
 *   1. [lastConnectedAtMs] DESC, nulls last (most-recently-used wins);
 *   2. [firstPairedAtMs] DESC (newest-paired tie-breaker).
 *
 * **Fingerprint:** stored as lowercase hex for direct comparison
 * with the editor's pairing UI display. The authoritative bytes live
 * inside [pairingUrl] (parsed on demand via [ru.sipaha.sawe.core.PairingUrl.parse]).
 */
@Serializable
data class PairedServer(
    val id: String,
    @SerialName("pairing_url") val pairingUrl: String,
    val label: String,
    @SerialName("fingerprint_hex") val fingerprintHex: String,
    @SerialName("first_paired_at_ms") val firstPairedAtMs: Long,
    @SerialName("last_connected_at_ms") val lastConnectedAtMs: Long? = null,
)
