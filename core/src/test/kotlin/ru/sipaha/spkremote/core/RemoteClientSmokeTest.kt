package ru.sipaha.spkremote.core

import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * `RemoteClient`'s WebSocket lifecycle is hard to unit-test without spinning
 * up a TLS-pinning capable server; that's the job of the integration test
 * gated behind `SPK_EDITOR_PAIRING_URL`. This smoke test only verifies the
 * surface contract — that `RemoteClient` constructs and exposes a usable
 * `notifications` flow before any connection happens.
 *
 * (The `call without connect` failure mode is covered by the matching test
 * in `RemoteClientLifecycleTest`; no need to duplicate it here.)
 */
class RemoteClientSmokeTest {

    @Test
    fun `can construct from a parsed pairing url`() {
        val url = PairingUrl(
            host = "127.0.0.1",
            port = 8443,
            secret = ByteArray(32) { it.toByte() },
            client = "smoke-test",
            fingerprint = ByteArray(32) { (255 - it).toByte() },
        )
        val client = RemoteClient(url)
        assertNotNull(client.notifications)
        client.close()
    }
}
