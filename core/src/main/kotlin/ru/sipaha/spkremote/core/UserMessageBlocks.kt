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
@Serializable
@JsonClassDiscriminator("type")
sealed class ContentBlockDto {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlockDto()

    @Serializable
    @SerialName("image")
    data class Image(
        val data: String,
        val mimeType: String,
    ) : ContentBlockDto()

    @Serializable
    @SerialName("resource_link")
    data class ResourceLink(
        val name: String,
        val uri: String,
        val description: String? = null,
    ) : ContentBlockDto()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val data: String,
        val mimeType: String,
    ) : ContentBlockDto()

    @Serializable
    @SerialName("resource")
    data class Resource(val resource: JsonObject) : ContentBlockDto()
}

/**
 * Result of encoding one user-picked attachment into a content block.
 *
 * [Failure] carries a user-facing reason — the caller surfaces it via
 * a snackbar and skips the failing attachment rather than aborting the
 * whole send. This keeps "one of three files refused → other two go
 * out" behaviour straightforward.
 */
sealed class AttachmentEncoding {
    data class Block(val block: ContentBlockDto) : AttachmentEncoding()
    data class Failure(val reason: String) : AttachmentEncoding()
}

/**
 * Convert one user-picked file ([bytes] / [mimeType] / [displayName])
 * into either a [ContentBlockDto] ready for `send_message_blocks` or
 * a [AttachmentEncoding.Failure] describing why the file can't be sent.
 *
 * Routing:
 *  - any `image/...` MIME → [ContentBlockDto.Image] with base64 of [bytes];
 *  - text-like MIME (any `text/...` prefix or one of the well-known
 *    `application/...` text formats below) → [ContentBlockDto.Text]
 *    containing the file's UTF-8 contents wrapped in a 4-backtick
 *    fenced block. The 4-backtick fence matches the desktop side
 *    (`conversation_render.rs`) — three-backtick blocks embedded in
 *    the file body don't break out.
 *  - everything else → [AttachmentEncoding.Failure] (binary not
 *    supported in V1 — would need a Resource block with proper
 *    blob encoding).
 *
 * Invalid UTF-8 in a text-like MIME also returns Failure, because
 * sending a corrupt string up the chain would only confuse the agent.
 */
fun encodeAttachment(
    bytes: ByteArray,
    mimeType: String,
    displayName: String,
): AttachmentEncoding {
    val normalised = mimeType.lowercase().trim()
    if (normalised.startsWith("image/")) {
        val data = Base64Codec.encodeNoWrap(bytes)
        return AttachmentEncoding.Block(
            ContentBlockDto.Image(data = data, mimeType = normalised),
        )
    }
    if (isTextLikeMime(normalised)) {
        val text = decodeStrictUtf8(bytes)
            ?: return AttachmentEncoding.Failure(
                "`$displayName` is not valid UTF-8 text",
            )
        val extension = extensionFromMime(normalised)
        val body = buildString {
            append("Attached `")
            append(displayName)
            append("`:\n\n")
            append("````")
            append(extension)
            append("\n")
            append(text)
            if (!text.endsWith("\n")) append("\n")
            append("````\n")
        }
        return AttachmentEncoding.Block(ContentBlockDto.Text(body))
    }
    return AttachmentEncoding.Failure(
        "binary file `$displayName` ($mimeType) is not yet supported",
    )
}

private fun isTextLikeMime(normalised: String): Boolean {
    if (normalised.startsWith("text/")) return true
    return normalised in TEXT_LIKE_APPLICATION_MIMES
}

private val TEXT_LIKE_APPLICATION_MIMES = setOf(
    "application/json",
    "application/xml",
    "application/x-yaml",
    "application/yaml",
    "application/javascript",
    "application/typescript",
    "application/sql",
    "application/x-sh",
    "application/toml",
    "application/x-toml",
)

/**
 * Decode [bytes] as strict UTF-8. Returns null when the byte sequence
 * contains invalid code units rather than substituting U+FFFD —
 * downstream we want to refuse the file with a clear error, not silently
 * forward a mojibake'd body to the LLM.
 */
private fun decodeStrictUtf8(bytes: ByteArray): String? {
    val decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return try {
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }
}

/**
 * Best-effort code-fence language hint derived from a MIME type. Empty
 * string when there's no good match — the fence is still valid without
 * a language tag.
 */
private fun extensionFromMime(normalised: String): String = when (normalised) {
    "text/x-rust" -> "rust"
    "text/x-kotlin", "text/kotlin" -> "kotlin"
    "text/x-java-source", "text/x-java" -> "java"
    "text/x-python", "text/x-script.python" -> "python"
    "text/x-c", "text/x-csrc" -> "c"
    "text/x-c++src", "text/x-c++" -> "cpp"
    "text/x-go" -> "go"
    "text/x-ruby" -> "ruby"
    "text/x-shellscript", "application/x-sh" -> "bash"
    "text/x-sql", "application/sql" -> "sql"
    "text/html" -> "html"
    "text/css" -> "css"
    "text/csv" -> "csv"
    "text/markdown", "text/x-markdown" -> "markdown"
    "text/xml", "application/xml" -> "xml"
    "text/yaml", "application/x-yaml", "application/yaml" -> "yaml"
    "text/javascript", "application/javascript" -> "javascript"
    "application/typescript" -> "typescript"
    "application/json" -> "json"
    "application/toml", "application/x-toml" -> "toml"
    "text/plain" -> ""
    else -> ""
}

/**
 * `:core` is a pure JVM module — we can't link `android.util.Base64`.
 * `java.util.Base64.getEncoder()` is standard-alphabet with padding, no
 * line wrapping by default (unlike `getMimeEncoder`), which matches
 * what the ACP server's Rust-side `base64::engine::general_purpose::STANDARD`
 * decoder expects.
 */
internal object Base64Codec {
    fun encodeNoWrap(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)
}
