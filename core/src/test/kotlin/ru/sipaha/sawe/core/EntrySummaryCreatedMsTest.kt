package ru.sipaha.sawe.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntrySummaryCreatedMsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes observer_nudge when present`() {
        val e = json.decodeFromString(
            EntrySummary.serializer(),
            """{"role":"user","preview":"Continue.","index":0,"observer_nudge":true}""",
        )
        assertTrue(e.observerNudge)
    }

    @Test
    fun `observer_nudge absent decodes to false`() {
        val e = json.decodeFromString(
            EntrySummary.serializer(),
            """{"role":"user","preview":"hi","index":0}""",
        )
        assertFalse(e.observerNudge)
    }

    @Test
    fun `decodes created_ms when present`() {
        val e = json.decodeFromString(
            EntrySummary.serializer(),
            """{"role":"user","preview":"hi","index":0,"created_ms":1716200000000}""",
        )
        assertEquals(1716200000000L, e.createdMs)
    }

    @Test
    fun `created_ms absent decodes to null`() {
        val e = json.decodeFromString(
            EntrySummary.serializer(),
            """{"role":"user","preview":"hi","index":0}""",
        )
        assertNull(e.createdMs)
    }
}
