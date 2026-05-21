package ru.sipaha.spkremote.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionBannerTextTest {
    @Test
    fun `connected hides the banner`() {
        assertNull(connectionBannerLabel(ConnectionState.Connected))
    }

    @Test
    fun `connecting shows подключение`() {
        assertEquals("Подключение…", connectionBannerLabel(ConnectionState.Connecting))
    }

    @Test
    fun `reconnecting first attempt omits attempt counter`() {
        val s = ConnectionState.Reconnecting(attempt = 1, nextRetryMs = 1000L)
        assertEquals("Переподключение…", connectionBannerLabel(s))
    }

    @Test
    fun `reconnecting later attempt includes attempt counter`() {
        val s = ConnectionState.Reconnecting(attempt = 3, nextRetryMs = 1000L)
        assertEquals("Переподключение… (попытка 3)", connectionBannerLabel(s))
    }

    @Test
    fun `disconnected shows нет связи`() {
        assertEquals("Нет связи", connectionBannerLabel(ConnectionState.Disconnected))
    }

    @Test
    fun `failed terminal shows нет связи`() {
        val s = ConnectionState.FailedTerminal(ConnectFailure.ProtocolError("bad version"))
        assertEquals("Нет связи", connectionBannerLabel(s))
    }

    @Test
    fun `last exchange shown only when disconnected with a timestamp`() {
        assertTrue(connectionBannerShowsLastExchange(ConnectionState.Disconnected, 123L))
        assertFalse(connectionBannerShowsLastExchange(ConnectionState.Disconnected, null))
        assertFalse(connectionBannerShowsLastExchange(ConnectionState.Connected, 123L))
    }
}
