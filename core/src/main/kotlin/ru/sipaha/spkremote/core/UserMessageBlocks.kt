@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ru.sipaha.spkremote.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

/**
 * Kotlin mirror of `acp::ContentBlock` (see
 * `agent-client-protocol-schema` crate, `content.rs`). Sent to the
 * server-side `remote.solution_agent.send_message_blocks` tool — the
 * server decodes this list directly as `Vec<acp::ContentBlock>`.
 *
 * **Wire shape** is dictated by the Rust side
 * `#[serde(tag = "type", rename_all = "snake_case")]` on the enum and
 * `#[serde(rename_all = "camelCase")]` on each tuple-variant inner
 * struct. Concretely:
 *
 *   - `{"type": "text", "text": "..."}` — TextContent inner. The
 *     `annotations` / `_meta` fields are optional and elided here.
 *   - `{"type": "image", "data": "<base64>", "mimeType": "image/png"}`
 *     — ImageContent's `mime_type` becomes `mimeType` because of the
 *     camelCase rename. `uri` / `annotations` / `_meta` optional, elided.
 *   - `{"type": "resource_link", "name": "...", "uri": "..."}` — name +
 *     uri are required on the Rust side; everything else optional. We
 *     keep `description` for future-proofing.
 *   - `{"type": "resource", "resource": {...}}` — the embedded resource
 *     payload is a polymorphic Text/Blob union; we pass it through as
 *     an opaque [JsonObject] to avoid pinning the inner schema here
 *     (the mobile picker doesn't yet emit Resource blocks; the variant
 *     is modelled only so the round-trip test covers every ACP arm).
 *   - `{"type": "audio", "data": "<base64>", "mimeType": "audio/wav"}`
 *     — same shape as Image.
 *
 * **Tuple-variant flattening note.** On the Rust side these are tuple
 * variants `Text(TextContent)`, `Image(ImageContent)`, … and `serde`
 * with `#[serde(tag = "type")]` flattens the inner struct's fields up
 * alongside the discriminator. The Kotlin sealed-class machinery
 * achieves the same flat shape because every variant here is itself a
 * plain `@Serializable` data class — kotlinx.serialization then writes
 * the discriminator next to the variant's own fields, matching the
 * Rust output one-to-one. The `JsonClassDiscriminator("type")`
 * annotation overrides the default discriminator name from `"type"`
 * (which already happens to be the kotlinx default) to be explicit
 * and self-documenting at the use site.
 */
/**
 * Per-variant optional `_meta` field. Mirrors `#[serde(rename = "_meta")]`
 * on each acp::ContentBlock variant — kotlinx-serialization with the
 * `JsonClassDiscriminator("type")` tag flattens our data-class fields up
 * next to the discriminator, so a `@SerialName("_meta")` field here lands
 * as `{"type": "text", "text": "...", "_meta": {...}}` on the wire,
 * matching the Rust side one-to-one.
 *
 * With `JsonRpc.json` configured as `explicitNulls = false`, a null value
 * is dropped from the wire — older servers that never set the field never
 * see it, and the wire shape for unstamped blocks is unchanged.
 *
 * The server-side ACP decoder treats `_meta` as opaque; it lifts only
 * `spk_client_send_id` from the first block of a user message's chunks.
 */
@Serializable
@JsonClassDiscriminator("type")
sealed class ContentBlockDto {
    /**
     * Per-variant `_meta` accessor — implemented by each leaf. Kept as a
     * sealed-class member rather than a generic helper so the call site
     * doesn't need a `when` over every variant; the visitor in
     * [stampClientSendId] uses it.
     */
    abstract val meta: JsonObject?

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        @SerialName("_meta") override val meta: JsonObject? = null,
    ) : ContentBlockDto()

    @Serializable
    @SerialName("image")
    data class Image(
        val data: String,
        val mimeType: String,
        @SerialName("_meta") override val meta: JsonObject? = null,
    ) : ContentBlockDto()

    @Serializable
    @SerialName("resource_link")
    data class ResourceLink(
        val name: String,
        val uri: String,
        val description: String? = null,
        @SerialName("_meta") override val meta: JsonObject? = null,
    ) : ContentBlockDto()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val data: String,
        val mimeType: String,
        @SerialName("_meta") override val meta: JsonObject? = null,
    ) : ContentBlockDto()

    @Serializable
    @SerialName("resource")
    data class Resource(
        val resource: JsonObject,
        @SerialName("_meta") override val meta: JsonObject? = null,
    ) : ContentBlockDto()
}

/**
 * Stamp [clientSendId] onto the FIRST block of [blocks] under the
 * `spk_client_send_id` key of its `_meta` object. Returns a new list —
 * other blocks are untouched.
 *
 * Server-side the ACP decoder reads the meta from the first non-null
 * block of the user message's chunks, so stamping only the head is
 * sufficient. If the first block already carries a `_meta` map, the
 * existing keys are preserved and the spk key is merged in (additive,
 * non-destructive).
 *
 * Pure / total — never throws, returns immutable lists.
 */
fun stampClientSendId(blocks: List<ContentBlockDto>, clientSendId: Long): List<ContentBlockDto> {
    if (blocks.isEmpty()) return blocks
    val first = blocks.first()
    val mergedMeta = kotlinx.serialization.json.buildJsonObject {
        first.meta?.forEach { (k, v) -> put(k, v) }
        put("spk_client_send_id", kotlinx.serialization.json.JsonPrimitive(clientSendId))
    }
    val stamped: ContentBlockDto = when (first) {
        is ContentBlockDto.Text -> first.copy(meta = mergedMeta)
        is ContentBlockDto.Image -> first.copy(meta = mergedMeta)
        is ContentBlockDto.ResourceLink -> first.copy(meta = mergedMeta)
        is ContentBlockDto.Audio -> first.copy(meta = mergedMeta)
        is ContentBlockDto.Resource -> first.copy(meta = mergedMeta)
    }
    return buildList(blocks.size) {
        add(stamped)
        for (i in 1 until blocks.size) add(blocks[i])
    }
}

// Note: an older `encodeAttachment` helper used to live here, converting
// user-picked file bytes into either an inline-base64 Image block or a
// 4-backtick-fenced Text block. It was removed once the mobile attach
// flow moved to the chunked-upload protocol (see UploadProtocol.kt) —
// the server now resolves `spk-upload://<id>` ResourceLink blocks back
// into Image / TextContent (the latter using the same fenced-code
// format), so the encode-locally-then-send-as-blocks path is dead.
//
// Don't reintroduce a local encoder here without also auditing the
// server's `send_message_blocks` resolver. The constraints that made
// the old path fragile (1 MiB MCP frame cap, base64 doubling small
// files into payloads larger than the cap, the cap forcing chunking
// anyway) are still in force.
