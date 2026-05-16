package ru.sipaha.spkremote.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertNotNull

/**
 * Opt-in integration test. Excluded from the default `:core:test` run via
 * JUnit's `excludeTags("integration")` filter in core/build.gradle.kts.
 *
 * Provide a pairing URL from a live editor instance:
 *
 *     SPK_EDITOR_PAIRING_URL='spk-remote://...' \
 *         ./gradlew :core:test -DincludeTags=integration
 */
@Tag("integration")
class LiveEditorIntegrationTest {

    @Test
    fun `handshakes and reads capabilities`() = runBlocking {
        val raw = System.getenv("SPK_EDITOR_PAIRING_URL")
        assumeTrue(!raw.isNullOrBlank(), "SPK_EDITOR_PAIRING_URL not set; skipping")

        val url = PairingUrl.parse(raw!!).getOrThrow()
        val client = RemoteClient(url)
        try {
            withTimeout(15_000) { client.connect().getOrThrow() }
            val resp = withTimeout(15_000) { client.call("remote.editor.capabilities") }
            assertNotNull(resp.result, "expected a non-null result for editor.capabilities")
        } finally {
            client.close()
        }
    }
}
