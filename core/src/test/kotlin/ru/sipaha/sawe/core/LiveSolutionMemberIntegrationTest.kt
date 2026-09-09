package ru.sipaha.sawe.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The whole "projects of a Solution" lifecycle against a REAL editor —
 * create a solution, create an empty project in it, read the member list
 * back, remove the project, delete the solution.
 *
 * This exists because the member contract broke in a way no unit test on
 * either side could see: the server renamed a member's identity from
 * `catalog_id` to `id` (plus `origin_catalog_id` for provenance) in
 * `a81166f241` and this client kept the old key. Both sides' own tests
 * stayed green; only a real round trip fails. The user found it by
 * creating a project on his phone and getting
 * *"Field 'catalog_id' is required … but it was missing"* — twice, since
 * the follow-up `solutions.get` was undecodable too.
 *
 * Opt-in, like [LiveEditorIntegrationTest]: excluded from `:core:test`
 * via `excludeTags("integration")`. Point it at a THROWAWAY editor — it
 * creates and deletes a Solution:
 *
 *     script/run-mcp --debug --headless --runtime-dir /tmp/sawe-pair-test &
 *     SPK_EDITOR_PAIRING_URL="$(cat /tmp/sawe-pair-test/pairing-url.txt)" \
 *         ./gradlew :core:test -DincludeTags=integration
 *
 * (Minting a pairing URL headlessly: write `clients` + `enabled: true`
 * into `<runtime-dir>/.spk/sawe-dev/config/remote-control.json` BEFORE
 * launch — the store's FS watcher starts the listener on the false→true
 * transition — then take `sha256` of the `remote-control.cert.der` it
 * generates as `server_fp`.)
 */
@Tag("integration")
class LiveSolutionMemberIntegrationTest {

    @Test
    fun `create solution, add an empty project, read it back, remove it`() = runBlocking {
        val raw = System.getenv("SPK_EDITOR_PAIRING_URL")
        assumeTrue(!raw.isNullOrBlank(), "SPK_EDITOR_PAIRING_URL not set; skipping")

        val url = PairingUrl.parse(raw!!).getOrThrow()
        val client = RemoteClient(url)
        var solutionId: Long? = null
        try {
            withTimeout(15_000) { client.connect().getOrThrow() }

            // 1. A scratch Solution to own the member.
            val created = withTimeout(15_000) {
                client.call(
                    "remote.solutions.create",
                    buildJsonObject { put("name", "wire-contract-probe") },
                )
            }
            assertNull(created.error, "solutions.create errored: ${created.error}")
            val createResult = JsonRpc.json.decodeFromJsonElement(
                CreateSolutionResult.serializer(),
                created.structuredContent()!!,
            )
            solutionId = createResult.solutionId

            // 2. The call that failed on the phone. Decoding the answer IS
            //    the assertion: a wrong key name throws here.
            val added = withTimeout(30_000) {
                client.addEmptyMember(solutionId, "probe-project")
            }
            assertTrue(added.memberId > 0, "expected a real member id, got ${added.memberId}")

            // 3. And the call that kept the projects screen stuck on the
            //    error afterwards. An empty project has no catalog row, so
            //    `origin_catalog_id` must be absent — and tolerated.
            val detail = withTimeout(15_000) {
                client.call(
                    "remote.solutions.get",
                    buildJsonObject { put("solution_id", solutionId) },
                )
            }
            assertNull(detail.error, "solutions.get errored: ${detail.error}")
            val solution = JsonRpc.json.decodeFromJsonElement(
                GetSolutionResult.serializer(),
                detail.structuredContent()!!,
            ).solution
            val member = solution.members.single()
            assertEquals(added.memberId, member.memberId, "add_empty_member id != solutions.get id")
            assertEquals("probe-project", member.name)
            assertNull(member.originCatalogId, "an empty project must have no catalog origin")
            assertTrue(
                member.localPath.endsWith("/probe-project"),
                "unexpected local_path: ${member.localPath}",
            )

            // 4. Removal identifies the row by member id alone. The server's
            //    params are deny_unknown_fields, so the old
            //    `solution_id + catalog_id` shape was rejected outright —
            //    this would throw if it came back.
            withTimeout(15_000) { client.removeMember(member.memberId) }

            val afterRemoval = withTimeout(15_000) {
                client.call(
                    "remote.solutions.get",
                    buildJsonObject { put("solution_id", solutionId) },
                )
            }
            val remaining = JsonRpc.json.decodeFromJsonElement(
                GetSolutionResult.serializer(),
                afterRemoval.structuredContent()!!,
            ).solution.members
            assertTrue(remaining.isEmpty(), "member survived remove_member: $remaining")
        } finally {
            // Best-effort teardown so a re-run starts clean even on failure.
            solutionId?.let { id ->
                runCatching {
                    withTimeout(15_000) {
                        client.call(
                            "remote.solutions.delete",
                            buildJsonObject { put("solution_id", id) },
                        )
                    }
                }
            }
            client.close()
        }
        assertNotNull(solutionId)
    }
}
