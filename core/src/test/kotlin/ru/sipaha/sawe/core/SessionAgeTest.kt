package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionAgeTest {
    private val now = 1_800_000_000_000L
    private fun ago(secs: Long) = compactSessionAge(now - secs * 1000, now)

    @Test
    fun `under a minute is now`() {
        assertEquals("now", ago(0))
        assertEquals("now", ago(59))
    }

    @Test
    fun `minutes hours and days switch at their boundaries`() {
        assertEquals("1m", ago(60))
        assertEquals("59m", ago(3_599))
        assertEquals("1h", ago(3_600))
        assertEquals("23h", ago(86_399))
        assertEquals("1d", ago(86_400))
    }

    @Test
    fun `days are capped at 99`() {
        assertEquals("99d", ago(99 * 86_400))
        assertEquals("99d", ago(400 * 86_400))
    }

    @Test
    fun `a future timestamp reads as now and a missing one as empty`() {
        assertEquals("now", compactSessionAge(now + 60_000, now))
        assertEquals("", compactSessionAge(0, now))
    }

    @Test
    fun `no label is longer than the widest one`() {
        val samples = listOf(0L, 59, 60, 3_599, 3_600, 86_399, 86_400, 9 * 86_400, 10 * 86_400, 10_000 * 86_400)
        for (s in samples) {
            assertTrue(ago(s).length <= WIDEST_SESSION_AGE.length, "label for ${s}s is ${ago(s)}")
        }
    }
}
