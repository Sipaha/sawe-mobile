package ru.sipaha.spkremote.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UserMessageBlocksTest {

    // ---- encodeAttachment branch coverage ----

    @Test
    fun `image bytes encode to an Image block carrying base64 + mimeType`() {
        // 4 raw bytes — 0xDE 0xAD 0xBE 0xEF base64-encodes to "3q2+7w==".
        val bytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val result = encodeAttachment(bytes, "image/png", "cat.png")
        val block = (result as AttachmentEncoding.Block).block
        assertTrue(block is ContentBlockDto.Image, "expected Image, got $block")
        assertEquals("3q2+7w==", block.data)
        assertEquals("image/png", block.mimeType)
    }

    @Test
    fun `image mime is lowercased before being placed on the wire`() {
        val result = encodeAttachment(byteArrayOf(0x00), "Image/JPEG", "x.jpg")
        val block = (result as AttachmentEncoding.Block).block as ContentBlockDto.Image
        assertEquals("image/jpeg", block.mimeType)
    }

    @Test
    fun `text-like bytes become a Text block with a 4-backtick fenced body`() {
        val body = "println(\"hi\")\n"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val result = encodeAttachment(bytes, "text/x-kotlin", "snippet.kt")
        val block = (result as AttachmentEncoding.Block).block
        assertTrue(block is ContentBlockDto.Text, "expected Text, got $block")
        val text = (block as ContentBlockDto.Text).text
        // Filename appears in the header, fence is exactly 4 backticks.
        assertTrue(text.contains("`snippet.kt`"), "missing filename in $text")
        assertTrue(text.contains("````kotlin\n"), "missing 4-backtick opener in $text")
        assertTrue(text.endsWith("````\n"), "missing 4-backtick closer in $text")
        // Three-backtick blocks embedded in the file body must not break out
        // of the outer 4-backtick fence — by construction they're shorter.
        assertTrue(text.contains(body), "file body absent from $text")
    }

    @Test
    fun `application_json is treated as a text-like mime`() {
        val result = encodeAttachment("{}".toByteArray(), "application/json", "x.json")
        val block = (result as AttachmentEncoding.Block).block
        assertTrue(block is ContentBlockDto.Text)
        assertTrue((block as ContentBlockDto.Text).text.contains("````json\n"))
    }

    @Test
    fun `binary mime returns Failure carrying the filename and mime`() {
        val result = encodeAttachment(byteArrayOf(0, 1, 2), "application/octet-stream", "blob.bin")
        val failure = result as AttachmentEncoding.Failure
        assertTrue(failure.reason.contains("blob.bin"), failure.reason)
        assertTrue(failure.reason.contains("application/octet-stream"), failure.reason)
    }

    @Test
    fun `invalid UTF-8 in a text-like mime returns Failure`() {
        // 0xC3 0x28 — first byte announces a 2-byte sequence, second byte
        // isn't a valid continuation. Strict UTF-8 decode must reject it.
        val bytes = byteArrayOf(0xC3.toByte(), 0x28.toByte())
        val result = encodeAttachment(bytes, "text/plain", "weird.txt")
        val failure = result as AttachmentEncoding.Failure
        assertTrue(failure.reason.contains("weird.txt"), failure.reason)
        assertTrue(failure.reason.contains("UTF-8"), failure.reason)
    }

    // ---- ContentBlockDto wire-shape round-trips ----

    private fun encode(block: ContentBlockDto): String =
        JsonRpc.json.encodeToString(ContentBlockDto.serializer(), block)

    private fun decode(text: String): ContentBlockDto =
        JsonRpc.json.decodeFromString(ContentBlockDto.serializer(), text)

    @Test
    fun `Text variant has the exact ACP wire shape`() {
        val json = encode(ContentBlockDto.Text("hello"))
        val parsed = JsonRpc.json.parseToJsonElement(json).jsonObject
        assertEquals("text", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("hello", parsed["text"]?.jsonPrimitive?.content)
        // Round-trip back to a typed object.
        val back = decode(json) as ContentBlockDto.Text
        assertEquals("hello", back.text)
    }

    @Test
    fun `Image variant emits camelCase mimeType per ACP ImageContent`() {
        val json = encode(ContentBlockDto.Image(data = "Zm9v", mimeType = "image/png"))
        val parsed = JsonRpc.json.parseToJsonElement(json).jsonObject
        assertEquals("image", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("Zm9v", parsed["data"]?.jsonPrimitive?.content)
        // CamelCase — NOT mime_type. Asserting this protects against a
        // future kotlinx-serialization upgrade flipping the discriminator
        // arrangement or someone "fixing" the field name to snake_case.
        assertEquals("image/png", parsed["mimeType"]?.jsonPrimitive?.content)
        val back = decode(json) as ContentBlockDto.Image
        assertEquals("image/png", back.mimeType)
    }

    @Test
    fun `ResourceLink emits required name and uri fields`() {
        val json = encode(
            ContentBlockDto.ResourceLink(name = "spec.md", uri = "file:///tmp/spec.md"),
        )
        val parsed = JsonRpc.json.parseToJsonElement(json).jsonObject
        assertEquals("resource_link", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("spec.md", parsed["name"]?.jsonPrimitive?.content)
        assertEquals("file:///tmp/spec.md", parsed["uri"]?.jsonPrimitive?.content)
        val back = decode(json) as ContentBlockDto.ResourceLink
        assertEquals("spec.md", back.name)
    }

    @Test
    fun `Audio variant has the same shape as Image`() {
        val json = encode(ContentBlockDto.Audio(data = "AA==", mimeType = "audio/wav"))
        val parsed = JsonRpc.json.parseToJsonElement(json).jsonObject
        assertEquals("audio", parsed["type"]?.jsonPrimitive?.content)
        assertEquals("audio/wav", parsed["mimeType"]?.jsonPrimitive?.content)
        val back = decode(json) as ContentBlockDto.Audio
        assertEquals("audio/wav", back.mimeType)
    }

    @Test
    fun `Resource variant carries an opaque nested object`() {
        val inner = buildJsonObject {
            put("uri", JsonPrimitive("file:///x"))
            put("text", JsonPrimitive("contents"))
            put("mimeType", JsonPrimitive("text/plain"))
        }
        val json = encode(ContentBlockDto.Resource(inner))
        val parsed = JsonRpc.json.parseToJsonElement(json).jsonObject
        assertEquals("resource", parsed["type"]?.jsonPrimitive?.content)
        val nested = parsed["resource"] as? JsonObject
        assertNotNull(nested)
        assertEquals("file:///x", nested["uri"]?.jsonPrimitive?.content)
        val back = decode(json) as ContentBlockDto.Resource
        assertEquals("contents", back.resource["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a list of mixed blocks round-trips intact`() {
        val mixed = listOf<ContentBlockDto>(
            ContentBlockDto.Text("look at this"),
            ContentBlockDto.Image(data = "Zm9v", mimeType = "image/png"),
        )
        val json = JsonRpc.json.encodeToString(
            ListSerializer(ContentBlockDto.serializer()),
            mixed,
        )
        val back = JsonRpc.json.decodeFromString(
            ListSerializer(ContentBlockDto.serializer()),
            json,
        )
        assertEquals(mixed, back)
    }

    @Test
    fun `ACP-shaped JSON decodes into the right Kotlin variant`() {
        // Verbatim what the server emits when round-tripping a Text block
        // through `serde_json::to_string(&acp::ContentBlock::Text(...))`.
        val acpEmitted = """{"type":"text","text":"hi"}"""
        val back = decode(acpEmitted) as ContentBlockDto.Text
        assertEquals("hi", back.text)
    }
}
