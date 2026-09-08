package ru.sipaha.sawe.app.vm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.core.EntryRoleDto
import ru.sipaha.sawe.core.EntrySummary
import ru.sipaha.sawe.core.Digests
import ru.sipaha.sawe.core.buildKnownEntries
import ru.sipaha.sawe.core.GetSessionChangesResult
import ru.sipaha.sawe.core.KnownEntryDto
import ru.sipaha.sawe.core.StreamIdDto

/**
 * The convergence RULE of the delta poll ([shouldRepollAfterSuccess]).
 *
 * This file covers only mechanism (c) of N-29 — the unreachable target. The
 * other two mechanisms are behavioural, not a predicate, and live in
 * [DeltaPollGateTest]: that a poke never cancels an in-flight poll (b), that N
 * pokes during one request collapse into ONE extra iteration rather than N,
 * and that the loop can always stop. Read the two together; neither alone
 * pins the finding.
 *
 * The bug this pins (N-29): the loop used to compare the PER-STREAM cursor it
 * holds against the SESSION-GLOBAL `change_seq` carried by
 * `agent_session_dirty`. The last dirty of every turn carries `Main.seq + 1`
 * (the `→Idle` transition bumps `change_seq` with no entry behind it), so the
 * target was unreachable and the loop re-polled with no delay for as long as
 * the chat stayed open — 1 request per RTT, each one re-downloading whole
 * entries.
 */
class DeltaPollConvergenceTest {

    @Test
    fun `a caught-up page ends the loop`() {
        assertFalse(
            shouldRepollAfterSuccess(hasMore = false, repollRequested = false),
            "has_more == false is the server certifying the selected stream is " +
                "caught up — re-polling from that cursor provably returns nothing",
        )
    }

    @Test
    fun `pagination keeps draining`() {
        assertTrue(
            shouldRepollAfterSuccess(hasMore = true, repollRequested = false),
            "a paginated catch-up must drain the next page from the advanced cursor",
        )
    }

    @Test
    fun `a push that landed mid-poll earns exactly one more iteration`() {
        assertTrue(
            shouldRepollAfterSuccess(hasMore = false, repollRequested = true),
            "single-flight never cancels an in-flight poll, so a push that arrives " +
                "while it is on the wire has to be honoured afterwards",
        )
        // ...and only one: the flag is consumed by the loop before this call,
        // so a caught-up page with no NEW request stops.
        assertFalse(shouldRepollAfterSuccess(hasMore = false, repollRequested = false))
    }

    @Test
    fun `both signals together still just continue`() {
        assertTrue(shouldRepollAfterSuccess(hasMore = true, repollRequested = true))
    }

    // ---- Entry-body deltas: a splice that cannot be trusted full-reloads ----

    private fun entry(
        index: Int,
        markdown: String? = null,
        prefixLen: Long? = null,
        tail: String? = null,
        len: Long? = null,
    ) = EntrySummary(
        role = EntryRoleDto.Assistant,
        index = index,
        markdown = markdown,
        markdownLen = len,
        markdownPrefixLen = prefixLen,
        markdownTail = tail,
    )

    /** The digest this client would have sent for [body] under [index]. */
    private fun offer(index: Int, body: String): KnownEntryDto {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return KnownEntryDto(
            index = index,
            markdownLen = bytes.size.toLong(),
            markdownHash = Digests.bodyDigest(bytes),
        )
    }

    private fun delta(vararg entries: EntrySummary) = GetSessionChangesResult(
        epoch = 3L,
        currentSeq = 4711L,
        reset = false,
        totalCount = entries.size,
        changedEntries = entries.toList(),
        selectedStreamId = StreamIdDto.Main,
    )

    @Test
    fun `a delta of whole bodies is passed through untouched`() {
        // The old-server / feature-off shape. Nothing to splice, so the plan
        // must be the SAME object — a copy here would silently drop any
        // future field the merge downstream reads.
        val incoming = delta(entry(index = 7, markdown = "whole body", len = 10L))
        val plan = planDeltaApply(held = listOf(entry(index = 7, markdown = "held")), delta = incoming)
        assertSame(incoming, (plan as DeltaApplyPlan.Apply).delta)
    }

    @Test
    fun `a tail is spliced onto the held body before anything downstream sees it`() {
        // Multi-byte base on purpose: the prefix length is UTF-8 BYTES, and a
        // UTF-16 `substring` would cut "é" in half and corrupt the transcript
        // with no error anywhere.
        val base = "héllo"                       // 6 bytes, 5 UTF-16 units
        val plan = planDeltaApply(
            held = listOf(entry(index = 42, markdown = base)),
            delta = delta(entry(index = 42, prefixLen = 6L, tail = " world", len = 12L)),
            offered = listOf(offer(42, base)),
        )
        val spliced = (plan as DeltaApplyPlan.Apply).delta.changedEntries.single()
        assertEquals("héllo world", spliced.markdown)
        assertEquals(null, spliced.markdownPrefixLen, "the transient fields must be cleared")
        assertEquals(null, spliced.markdownTail)
    }

    @Test
    fun `an unchanged body still round-trips, whatever the tail happens to be`() {
        // Two shapes for the same fact, and the splice must not care which.
        //
        // An empty tail is the shape §2.4 documents, and the one a client that
        // digested the WHOLE held body would get. It is NOT the shape a real
        // assistant entry produces: the digest is taken over the held body
        // minus its trailing whitespace run (the rendered markdown's trailing
        // newlines move as the body grows, so including them means the held
        // bytes stop being a prefix and nothing ever matches), so an unchanged
        // reply comes back with those newlines AS the tail.
        //
        // Hence: never short-circuit on `tail == ""`. "Unchanged" is
        // `markdown_len == held_len`, and the splice already produces exactly
        // that without needing to know.
        val emptyTail = planDeltaApply(
            held = listOf(entry(index = 42, markdown = "unchanged")),
            delta = delta(entry(index = 42, prefixLen = 9L, tail = "", len = 9L)),
            offered = listOf(offer(42, "unchanged")),
        )
        assertEquals("unchanged", (emptyTail as DeltaApplyPlan.Apply).delta.changedEntries.single().markdown)

        // The trim means the offer describes "unchanged" (9 bytes) while the
        // held body is "unchanged\n\n" (11), so the tail carries the newlines
        // back. The offer's length is what `prefix_len` must echo.
        val trailingNewlines = planDeltaApply(
            held = listOf(entry(index = 42, markdown = "unchanged\n\n")),
            delta = delta(entry(index = 42, prefixLen = 9L, tail = "\n\n", len = 11L)),
            offered = listOf(offer(42, "unchanged")),
        )
        assertEquals(
            "unchanged\n\n",
            (trailingNewlines as DeltaApplyPlan.Apply).delta.changedEntries.single().markdown,
            "a non-empty tail that reproduces the held body byte-for-byte is " +
                "the ordinary unchanged case, not a change",
        )
    }

    @Test
    fun `a tail with no held base full-reloads instead of publishing a hole`() {
        // Cache miss / a window that never contained index 42. There is
        // nothing to append to, and publishing the tail alone would render a
        // bubble containing only the last 96 bytes of the reply.
        val plan = planDeltaApply(
            held = emptyList(),
            delta = delta(entry(index = 42, prefixLen = 8L, tail = " tail", len = 13L)),
            offered = listOf(offer(42, "held bo")),
        )
        assertTrue(plan is DeltaApplyPlan.FullReload)
    }

    @Test
    fun `every protocol violation full-reloads, and none of them publishes`() {
        val held = listOf(entry(index = 42, markdown = "held body"))
        val violations = mapOf(
            "prefix_len without tail" to entry(index = 42, prefixLen = 9L),
            "both a body and a tail" to entry(index = 42, markdown = "x", prefixLen = 9L, tail = "y"),
            "prefix_len past the held body" to entry(index = 42, prefixLen = 999L, tail = "y"),
            "length that disagrees with the splice" to
                entry(index = 42, prefixLen = 9L, tail = "!", len = 1_000L),
            "a prefix_len for an index we never offered" to
                entry(index = 7, prefixLen = 9L, tail = "!", len = 10L),
        )
        val offered = listOf(offer(42, "held body"))
        for ((name, bad) in violations) {
            assertTrue(
                planDeltaApply(held, delta(bad), offered) is DeltaApplyPlan.FullReload,
                "$name must full-reload: a half-spliced window is a silently " +
                    "corrupted transcript, which is worse than one extra get_session",
            )
        }
    }

    @Test
    fun `an orphan tail is caught even when it is the only one on the page`() {
        // `rehydrateEntryBodies` short-circuits on "no entry carries a
        // prefix_len", so on a page of purely-tail entries it would return Ok
        // and those entries would reach the UI with `markdown == null` — empty
        // bubbles. The page-wide scan in `planDeltaApply` closes that: the
        // cheap exit tests for a tail as well as a prefix_len.
        val incoming = delta(entry(index = 42, tail = "orphan"))
        assertTrue(
            planDeltaApply(listOf(entry(index = 42, markdown = "held")), incoming)
                is DeltaApplyPlan.FullReload,
        )
    }

    // ---- The base a splice lands on must still be the base we described ----

    @Test
    fun `a held body rewritten while the poll was on the wire full-reloads`() {
        // The invariant no length check can enforce. The server sets
        // `markdown_len == prefix_len + tail.length`, so a base of the RIGHT
        // LENGTH but the wrong bytes satisfies every arithmetic check and the
        // corrupted body would be published, cached, and digested into the
        // next poll. Only re-digesting the base against the offer catches it.
        val offeredBase = "held body"                       // 9 bytes
        val rewrittenSameLength = "HELD BODY"               // 9 bytes, different bytes
        val plan = planDeltaApply(
            held = listOf(entry(index = 42, markdown = rewrittenSameLength)),
            delta = delta(entry(index = 42, prefixLen = 9L, tail = "!", len = 10L)),
            offered = listOf(offer(42, offeredBase)),
        )
        assertTrue(
            plan is DeltaApplyPlan.FullReload,
            "a base that changed under the poll must full-reload, not splice — " +
                "the length check structurally cannot see this",
        )
        assertTrue((plan as DeltaApplyPlan.FullReload).reason.contains("base changed"))
    }

    @Test
    fun `an intact base still splices - the check is not just rejecting everything`() {
        val base = "held body"
        val plan = planDeltaApply(
            held = listOf(entry(index = 42, markdown = base)),
            delta = delta(entry(index = 42, prefixLen = 9L, tail = "!", len = 10L)),
            offered = listOf(offer(42, base)),
        )
        assertEquals("held body!", (plan as DeltaApplyPlan.Apply).delta.changedEntries.single().markdown)
    }

    @Test
    fun `a prefix_len that does not echo our offer full-reloads`() {
        // §2.4: the server echoes the caller's own `markdown_len` back as
        // `markdown_prefix_len`. A different value means it verified something
        // other than what we described, so the tail belongs to bytes we never
        // offered.
        val base = "held body"
        val plan = planDeltaApply(
            held = listOf(entry(index = 42, markdown = base)),
            delta = delta(entry(index = 42, prefixLen = 4L, tail = "!", len = 5L)),
            offered = listOf(offer(42, base)),
        )
        assertTrue(plan is DeltaApplyPlan.FullReload)
    }

    @Test
    fun `a whole-body page costs nothing and is still passed through untouched`() {
        // The cheap exit: no prefix_len and no tail anywhere means one scan,
        // no digests, and the SAME object handed back. This is every poll
        // against a server without the feature.
        val incoming = delta(entry(index = 7, markdown = "whole", len = 5L))
        assertSame(
            incoming,
            (planDeltaApply(listOf(entry(index = 7, markdown = "held")), incoming) as DeltaApplyPlan.Apply).delta,
        )
    }

    @Test
    fun `one bad entry poisons the whole page, not just itself`() {
        // All-or-nothing on purpose. Applying the good entries and reloading
        // for the bad one would publish an intermediate window — the exact
        // thing the full-reload exists to avoid.
        val held = listOf(entry(index = 41, markdown = "base41"), entry(index = 42, markdown = "base42"))
        val plan = planDeltaApply(
            held,
            delta(
                entry(index = 41, prefixLen = 6L, tail = "-ok", len = 9L),
                entry(index = 42, tail = "orphan"),
            ),
            offered = listOf(offer(41, "base41"), offer(42, "base42")),
        )
        assertTrue(plan is DeltaApplyPlan.FullReload)
    }

    @Test
    fun `the real offer builder and the splice verifier agree end to end`() {
        // Drives the loop with the ACTUAL `buildKnownEntries` rather than a
        // hand-rolled digest, so the client's trailing-whitespace trim policy
        // and the verification of the base can never drift apart: both derive
        // from the same offer. Shaped like a real streaming assistant entry —
        // over the 512-byte floor and rendered with trailing newlines.
        val body = "## Assistant\n\n" + "streamed text. ".repeat(40)
        val held = listOf(entry(index = 42, markdown = body + "\n\n"))

        val offered = buildKnownEntries(held)
        val offer = offered.single()

        // What the server would answer: it echoes our length back and hands
        // over everything after it.
        val grown = body + " and more.\n\n"
        val tail = String(
            grown.toByteArray(Charsets.UTF_8),
            offer.markdownLen.toInt(),
            grown.toByteArray(Charsets.UTF_8).size - offer.markdownLen.toInt(),
            Charsets.UTF_8,
        )
        val plan = planDeltaApply(
            held = held,
            delta = delta(
                entry(
                    index = 42,
                    prefixLen = offer.markdownLen,
                    tail = tail,
                    len = grown.toByteArray(Charsets.UTF_8).size.toLong(),
                ),
            ),
            offered = offered,
        )
        assertEquals(grown, (plan as DeltaApplyPlan.Apply).delta.changedEntries.single().markdown)
    }
}
