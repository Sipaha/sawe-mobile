package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Shared entry builder for the [WireFeature.ENTRY_BODY_DELTA] tests. */
internal fun entryOf(
    index: Int,
    markdown: String? = null,
    role: EntryRoleDto = EntryRoleDto.Assistant,
    preview: String = "",
    markdownLen: Long? = null,
    markdownPrefixLen: Long? = null,
    markdownTail: String? = null,
): EntrySummary = EntrySummary(
    role = role,
    preview = preview,
    index = index,
    markdown = markdown,
    markdownLen = markdownLen,
    markdownPrefixLen = markdownPrefixLen,
    markdownTail = markdownTail,
)

/** Alias kept short because [DigestsTest] uses it too. */
internal fun entry(index: Int, markdown: String?): EntrySummary = entryOf(index, markdown)

/** A body comfortably past [MIN_DELTA_BODY_BYTES]. */
private fun longBody(seed: String = "x"): String = seed.repeat(MIN_DELTA_BODY_BYTES + 16)

private fun deltaOf(vararg entries: EntrySummary) = GetSessionChangesResult(
    epoch = 1,
    currentSeq = 10,
    reset = false,
    totalCount = entries.size,
    changedEntries = entries.toList(),
    selectedStreamId = StreamIdDto.Main,
)

class BuildKnownEntriesTest {

    @Test
    fun `skips short bodies, null markdown and the index sentinel`() {
        val held = listOf(
            entryOf(index = 0, markdown = null),
            entryOf(index = 1, markdown = "too short"),
            entryOf(index = -1, markdown = longBody()),
            entryOf(index = 3, markdown = longBody()),
        )
        val known = buildKnownEntries(held)
        assertEquals(listOf(3), known.map { it.index })
    }

    /**
     * `MIN_DELTA_BODY_BYTES` is a BYTE threshold, so a body that is long
     * enough in UTF-16 units but short in bytes must still be skipped — and
     * vice versa. Exactly-at-the-threshold is included.
     */
    @Test
    fun `threshold is measured in utf8 bytes`() {
        val exactly = "a".repeat(MIN_DELTA_BODY_BYTES)
        val oneShort = "a".repeat(MIN_DELTA_BODY_BYTES - 1)
        assertEquals(1, buildKnownEntries(listOf(entryOf(0, exactly))).size)
        assertEquals(0, buildKnownEntries(listOf(entryOf(0, oneShort))).size)

        // 256 two-byte chars = 512 bytes but only 256 UTF-16 units: a
        // `String.length` threshold would wrongly skip this one.
        val twoByteChars = "é".repeat(MIN_DELTA_BODY_BYTES / 2)
        assertTrue(twoByteChars.length < MIN_DELTA_BODY_BYTES)
        assertEquals(1, buildKnownEntries(listOf(entryOf(0, twoByteChars))).size)
    }

    @Test
    fun `caps at KNOWN_ENTRIES_MAX and keeps the newest`() {
        val held = (0 until KNOWN_ENTRIES_MAX * 3).map { entryOf(it, longBody()) }
        val known = buildKnownEntries(held)
        assertEquals(KNOWN_ENTRIES_MAX, known.size)
        // Newest first, and the oldest entries are the ones dropped.
        val newest = (KNOWN_ENTRIES_MAX * 3 - 1) downTo (KNOWN_ENTRIES_MAX * 2)
        assertEquals(newest.toList(), known.map { it.index })
    }

    @Test
    fun `digest and length describe exactly the held bytes`() {
        val body = longBody("é")
        val known = buildKnownEntries(listOf(entryOf(7, body))).single()
        val bytes = body.toByteArray(Charsets.UTF_8)
        assertEquals(bytes.size.toLong(), known.markdownLen)
        assertEquals(Digests.bodyDigest(bytes), known.markdownHash)
        assertEquals(7, known.index)
    }

    @Test
    fun `empty held window yields an empty offer`() {
        assertEquals(emptyList(), buildKnownEntries(emptyList()))
    }

    // -----------------------------------------------------------------
    // The trailing-whitespace trim — what makes the feature save anything
    // -----------------------------------------------------------------

    /**
     * Stand-in for the server's verification step (`summarize_entry`): does
     * the offer describe a real prefix of the body the server now holds, and
     * if so what tail does it send? Byte-exact with the Rust side — it
     * hashes the first `markdownLen` bytes and compares the hex.
     */
    private fun serverTailFor(offer: KnownEntryDto, serverBody: String): String? {
        val bytes = serverBody.toByteArray(Charsets.UTF_8)
        val len = offer.markdownLen.toInt()
        if (len > bytes.size) return null
        if (Digests.bodyDigest(bytes.copyOf(len)) != offer.markdownHash) return null
        return String(bytes, len, bytes.size - len, Charsets.UTF_8)
    }

    /** Exactly how `session_entry_to_markdown` renders an assistant entry. */
    private fun assistantRendering(body: String) = "## Assistant\n\n$body\n\n"

    /**
     * The regression this exists for. The server wraps the body in a
     * trailing `"\n\n"`, so growth is NOT a pure append to the previous
     * rendering — the whitespace run moves and the held bytes differ from
     * the grown ones two bytes from the held end. Offering the trimmed
     * prefix is what keeps the match alive.
     */
    @Test
    fun `a growing assistant rendering still matches because the offer is trimmed`() {
        val held = assistantRendering("a".repeat(MIN_DELTA_BODY_BYTES + 40))
        val grown = assistantRendering("a".repeat(MIN_DELTA_BODY_BYTES + 40) + " plus more")

        val offer = buildKnownEntries(listOf(entryOf(42, held))).single()
        val tail = serverTailFor(offer, grown)

        assertNotNull(tail, "the trimmed offer must still be a prefix of the grown rendering")
        assertEquals(" plus more\n\n", tail)
        // And the client reconstructs the grown body byte-for-byte.
        val delta = deltaOf(
            entryOf(
                index = 42,
                markdownLen = grown.toByteArray(Charsets.UTF_8).size.toLong(),
                markdownPrefixLen = offer.markdownLen,
                markdownTail = tail,
            ),
        )
        val result = rehydrateEntryBodies(listOf(entryOf(42, held)), delta)
        assertIs<BodyRehydration.Ok>(result)
        assertEquals(grown, result.delta.changedEntries.single().markdown)
    }

    /**
     * The companion that documents WHY the trim is needed: offering the
     * whole held rendering stops matching the moment the body grows, so
     * every poll would fall back to a whole body — correct, but a saving of
     * exactly zero on the traffic N-29 targets. Mirrors the Rust
     * `delta_body_whole_held_body_stops_matching_once_the_body_grows`.
     */
    @Test
    fun `offering the whole held rendering would stop matching once it grows`() {
        val held = assistantRendering("a".repeat(MIN_DELTA_BODY_BYTES + 40))
        val grown = assistantRendering("a".repeat(MIN_DELTA_BODY_BYTES + 40) + " plus more")
        val heldBytes = held.toByteArray(Charsets.UTF_8)

        val wholeBodyOffer = KnownEntryDto(
            index = 42,
            markdownLen = heldBytes.size.toLong(),
            markdownHash = Digests.bodyDigest(heldBytes),
        )
        assertNull(
            serverTailFor(wholeBodyOffer, grown),
            "sanity: the untrimmed offer is exactly the case that fails",
        )
        // What buildKnownEntries actually offers is shorter than that.
        val actual = buildKnownEntries(listOf(entryOf(42, held))).single()
        assertTrue(actual.markdownLen < wholeBodyOffer.markdownLen)
        assertEquals(heldBytes.size.toLong() - 2, actual.markdownLen)
    }

    /**
     * The common case during a state-only dirty poke, and the branch a real
     * client almost always takes: the body did not change, so the trimmed
     * offer comes back with the trailing whitespace as its tail.
     *
     * Asserts the whole round trip, not just the tail string — an assertion
     * on the tail alone would not catch a splice that failed to reproduce
     * the held body.
     */
    @Test
    fun `an unchanged rendering yields the trailing whitespace as the tail`() {
        val held = assistantRendering("a".repeat(MIN_DELTA_BODY_BYTES + 40))
        val heldLen = held.toByteArray(Charsets.UTF_8).size.toLong()
        val offer = buildKnownEntries(listOf(entryOf(7, held))).single()

        val tail = serverTailFor(offer, held)
        assertEquals("\n\n", tail)

        val delta = deltaOf(
            entryOf(
                index = 7,
                markdownLen = heldLen,
                markdownPrefixLen = offer.markdownLen,
                markdownTail = tail,
            ),
        )
        val result = rehydrateEntryBodies(listOf(entryOf(7, held)), delta)
        assertIs<BodyRehydration.Ok>(result)
        assertEquals(held, result.delta.changedEntries.single().markdown)
    }

    /**
     * The honest "did this entry actually change" test is `markdownLen`
     * against the held length — never `markdownTail.isEmpty()`. Pinned
     * because the KDoc on [EntrySummary.markdownTail] promises it: after
     * the trailing-whitespace trim an UNCHANGED entry carries a NON-empty
     * tail, so a reader who branched on emptiness would get it backwards on
     * the common case.
     */
    @Test
    fun `an unchanged entry is identified by markdown_len, not by an empty tail`() {
        val held = assistantRendering("a".repeat(MIN_DELTA_BODY_BYTES + 40))
        val grown = assistantRendering("a".repeat(MIN_DELTA_BODY_BYTES + 40) + " more")
        val heldLen = held.toByteArray(Charsets.UTF_8).size.toLong()
        val offer = buildKnownEntries(listOf(entryOf(7, held))).single()

        val unchangedTail = serverTailFor(offer, held)
        val grownTail = serverTailFor(offer, grown)

        // The signal that works: length equality.
        assertEquals(heldLen, offer.markdownLen + unchangedTail!!.toByteArray(Charsets.UTF_8).size)
        assertTrue(offer.markdownLen + grownTail!!.toByteArray(Charsets.UTF_8).size > heldLen)

        // The signal that does NOT work: both tails are non-empty, so
        // emptiness distinguishes nothing here.
        assertTrue(unchangedTail.isNotEmpty())
        assertTrue(grownTail.isNotEmpty())
    }

    /** The trim only ever removes ASCII, so the offset stays a char boundary. */
    @Test
    fun `the trimmed offset lands on a utf8 character boundary`() {
        val held = assistantRendering("é".repeat(MIN_DELTA_BODY_BYTES))
        val offer = buildKnownEntries(listOf(entryOf(1, held))).single()
        val bytes = held.toByteArray(Charsets.UTF_8)
        val prefix = bytes.copyOf(offer.markdownLen.toInt())
        // Decoding and re-encoding is lossless exactly when the cut is on a
        // boundary; a cut inside the two-byte "é" would round-trip to U+FFFD.
        val decoded = String(prefix, Charsets.UTF_8)
        assertTrue(decoded.toByteArray(Charsets.UTF_8).contentEquals(prefix))
        assertFalse(decoded.contains('�'))
    }

    @Test
    fun `an all-whitespace body is skipped rather than offered at length zero`() {
        val blank = "\n".repeat(MIN_DELTA_BODY_BYTES * 2)
        assertEquals(emptyList(), buildKnownEntries(listOf(entryOf(3, blank))))
    }

    /**
     * The threshold applies to the OFFERED prefix: a body that only clears
     * it thanks to trailing newlines would save nothing worth the ~70 bytes
     * the offer costs.
     */
    @Test
    fun `the threshold is measured against the trimmed prefix`() {
        val body = "a".repeat(MIN_DELTA_BODY_BYTES - 1) + "\n".repeat(50)
        assertTrue(body.toByteArray(Charsets.UTF_8).size > MIN_DELTA_BODY_BYTES)
        assertEquals(emptyList(), buildKnownEntries(listOf(entryOf(4, body))))
    }
}

class RehydrateEntryBodiesTest {

    @Test
    fun `passes a whole-body delta straight through, untouched`() {
        val delta = deltaOf(entryOf(4, markdown = "whole", markdownLen = 5))
        val result = rehydrateEntryBodies(held = emptyList(), delta = delta)
        assertIs<BodyRehydration.Ok>(result)
        // Not merely equal — the same instance, i.e. no work was done at all
        // for a server that does not implement the feature.
        assertSame(delta, result.delta)
    }

    @Test
    fun `appends the tail and is multi-byte safe`() {
        // Base ends in a two-byte char, so a UTF-16 substring would cut in
        // the wrong place and produce a different string.
        val base = "первый абзац é"
        val tail = " — и хвост"
        val full = base + tail
        val baseLen = base.toByteArray(Charsets.UTF_8).size.toLong()

        val delta = deltaOf(
            entryOf(
                index = 2,
                markdownLen = full.toByteArray(Charsets.UTF_8).size.toLong(),
                markdownPrefixLen = baseLen,
                markdownTail = tail,
            ),
        )
        val result = rehydrateEntryBodies(held = listOf(entryOf(2, base)), delta = delta)
        assertIs<BodyRehydration.Ok>(result)
        val merged = result.delta.changedEntries.single()
        assertEquals(full, merged.markdown)
        // The transient fields are cleared, so nothing downstream — the
        // published window, the disk cache — ever sees them.
        assertNull(merged.markdownPrefixLen)
        assertNull(merged.markdownTail)
        assertTrue(baseLen > base.length.toLong(), "sanity: the base is not pure ASCII")
    }

    /**
     * `""` splices to the held body unchanged. It is a legal tail, not a
     * signal: after the trailing-whitespace trim in [buildKnownEntries] an
     * unchanged entry actually comes back with `"\n\n"`, so nothing may
     * branch on the tail being empty.
     */
    @Test
    fun `an empty tail splices back to exactly the held body`() {
        val base = longBody()
        val baseLen = base.toByteArray(Charsets.UTF_8).size.toLong()
        val delta = deltaOf(
            entryOf(
                index = 1,
                markdownLen = baseLen,
                markdownPrefixLen = baseLen,
                markdownTail = "",
            ),
        )
        val result = rehydrateEntryBodies(listOf(entryOf(1, base)), delta)
        assertIs<BodyRehydration.Ok>(result)
        assertEquals(base, result.delta.changedEntries.single().markdown)
    }

    @Test
    fun `mixes delta and whole bodies in one page`() {
        val base = "held body"
        val delta = deltaOf(
            entryOf(1, markdown = "brand new"),
            entryOf(
                index = 2,
                markdownPrefixLen = base.toByteArray(Charsets.UTF_8).size.toLong(),
                markdownTail = "+tail",
            ),
            entryOf(3, markdown = null),
        )
        val result = rehydrateEntryBodies(listOf(entryOf(2, base)), delta)
        assertIs<BodyRehydration.Ok>(result)
        assertEquals(
            listOf("brand new", "held body+tail", null),
            result.delta.changedEntries.map { it.markdown },
        )
    }

    @Test
    fun `broken on a missing base body`() {
        val delta = deltaOf(entryOf(9, markdownPrefixLen = 0, markdownTail = "tail"))
        val result = rehydrateEntryBodies(held = emptyList(), delta = delta)
        assertIs<BodyRehydration.Broken>(result, "expected Broken, got $result")
        assertTrue(result.reason.contains("no held body"))
    }

    @Test
    fun `broken when markdown and tail arrive together`() {
        val delta = deltaOf(
            entryOf(1, markdown = "whole", markdownPrefixLen = 0, markdownTail = "tail"),
        )
        val result = rehydrateEntryBodies(listOf(entryOf(1, "base")), delta)
        assertIs<BodyRehydration.Broken>(result)
        assertTrue(result.reason.contains("both markdown and tail"))
    }

    @Test
    fun `broken on a tail without a prefix length`() {
        val delta = deltaOf(
            entryOf(1, markdownPrefixLen = 0, markdownTail = ""),
            entryOf(2, markdownTail = "orphan"),
        )
        val result = rehydrateEntryBodies(listOf(entryOf(1, "")), delta)
        assertIs<BodyRehydration.Broken>(result)
        assertTrue(result.reason.contains("tail without prefix_len"))
    }

    @Test
    fun `broken on a prefix length without a tail`() {
        val delta = deltaOf(entryOf(1, markdownPrefixLen = 2))
        val result = rehydrateEntryBodies(listOf(entryOf(1, "base")), delta)
        assertIs<BodyRehydration.Broken>(result)
        assertTrue(result.reason.contains("prefix_len without tail"))
    }

    @Test
    fun `broken when the prefix length runs past the held body`() {
        val delta = deltaOf(entryOf(1, markdownPrefixLen = 99, markdownTail = "tail"))
        val result = rehydrateEntryBodies(listOf(entryOf(1, "base")), delta)
        assertIs<BodyRehydration.Broken>(result)
        assertTrue(result.reason.contains("out of range"))
    }

    @Test
    fun `broken when the spliced body does not measure markdown_len`() {
        val delta = deltaOf(
            entryOf(
                index = 1,
                markdownLen = 1000,
                markdownPrefixLen = 4,
                markdownTail = "tail",
            ),
        )
        val result = rehydrateEntryBodies(listOf(entryOf(1, "base")), delta)
        assertIs<BodyRehydration.Broken>(result)
        assertTrue(result.reason.contains("length mismatch"))
    }

    /**
     * The loop that closes: the body a rehydrated entry carries is exactly
     * the body the server now holds, so the digest the NEXT poll offers
     * matches what the server will compute — otherwise the second delta of a
     * streaming reply would always miss and fall back to a whole body.
     */
    @Test
    fun `a rehydrated body feeds the next poll's digest`() {
        val base = longBody()
        val tail = " …plus a freshly streamed sentence."
        val grown = base + tail
        val baseBytes = base.toByteArray(Charsets.UTF_8)

        // What the client would have offered for the base.
        val firstOffer = buildKnownEntries(listOf(entryOf(5, base))).single()
        assertEquals(Digests.bodyDigest(baseBytes), firstOffer.markdownHash)

        val delta = deltaOf(
            entryOf(
                index = 5,
                markdownLen = grown.toByteArray(Charsets.UTF_8).size.toLong(),
                markdownPrefixLen = firstOffer.markdownLen,
                markdownTail = tail,
            ),
        )
        val rehydrated = rehydrateEntryBodies(listOf(entryOf(5, base)), delta)
        assertIs<BodyRehydration.Ok>(rehydrated)

        val nextOffer = buildKnownEntries(rehydrated.delta.changedEntries).single()
        assertEquals(grown.toByteArray(Charsets.UTF_8).size.toLong(), nextOffer.markdownLen)
        assertEquals(
            Digests.bodyDigest(grown.toByteArray(Charsets.UTF_8)),
            nextOffer.markdownHash,
        )
    }
}
