package ru.sipaha.sawe.app.ui.solutions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sipaha.sawe.core.SolutionMember

/**
 * The new-session working-directory choices are the solution's member
 * projects — and nothing else. The solution root used to head this list; it
 * was removed so a mobile-started session always runs inside one project
 * worktree.
 *
 * Fixtures deliberately use paths whose last segment differs from the
 * member's id. The old fixture derived the path FROM the id
 * (`member(1L)` -> `/home/u/.spk/sol/1`), so label and directory name
 * coincided and the "label is the id" regression was invisible here.
 */
class CwdOptionsTest {

    private fun member(id: Long, path: String) = SolutionMember(
        memberId = id,
        name = "project-$id",
        localPath = path,
        status = "ok",
    )

    @Test
    fun `options are exactly the member projects labelled by directory name`() {
        val options = cwdOptionsFor(
            listOf(
                member(1L, "/home/u/.spk/sol/sawe"),
                member(2L, "/home/u/.spk/sol/spk-editor-mobile"),
            ),
        )

        assertEquals(
            listOf(
                CwdOption("sawe", "/home/u/.spk/sol/sawe"),
                CwdOption("spk-editor-mobile", "/home/u/.spk/sol/spk-editor-mobile"),
            ),
            options,
        )
    }

    @Test
    fun `label is never the numeric catalog id`() {
        val options = cwdOptionsFor(listOf(member(7L, "/home/u/.spk/sol/sawe")))

        // Regression guard for the String->Long catalogId migration, which
        // turned this picker into a list of "1", "2", ...
        assertEquals("sawe", options.single().label)
        assertTrue(options.none { it.label.toLongOrNull() != null })
    }

    @Test
    fun `a trailing separator does not produce an empty label`() {
        val options = cwdOptionsFor(listOf(member(1L, "/home/u/.spk/sol/sawe/")))

        assertEquals("sawe", options.single().label)
        // The path itself is passed through untouched — it is the wire `cwd`.
        assertEquals("/home/u/.spk/sol/sawe/", options.single().path)
    }

    @Test
    fun `a path with no separator is used whole as the label`() {
        val options = cwdOptionsFor(listOf(member(1L, "sawe")))

        assertEquals(CwdOption("sawe", "sawe"), options.single())
    }

    @Test
    fun `a root path falls back to the path itself`() {
        // "/" trims to "" — the fallback keeps the label non-empty rather
        // than rendering a blank row.
        assertEquals(CwdOption("/", "/"), cwdOptionsFor(listOf(member(1L, "/"))).single())
    }

    @Test
    fun `solution root is never offered`() {
        val options = cwdOptionsFor(listOf(member(1L, "/home/u/.spk/sol/sawe")))

        assertTrue(options.none { it.label.contains("root", ignoreCase = true) })
        // The member's own path is the deepest thing offered — no parent dir.
        assertTrue(options.all { it.path.endsWith("/sawe") })
    }

    @Test
    fun `a solution with no members offers nothing`() {
        // Empty options => the dialog hides the picker and sends no `cwd`,
        // leaving the choice to the server rather than defaulting to the root.
        assertEquals(emptyList<CwdOption>(), cwdOptionsFor(emptyList()))
    }
}
