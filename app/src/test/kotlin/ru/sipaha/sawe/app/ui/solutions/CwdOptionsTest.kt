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
 */
class CwdOptionsTest {

    private fun member(id: String) = SolutionMember(
        catalogId = id,
        localPath = "/home/u/.spk/sol/$id",
        status = "ready",
    )

    @Test
    fun `options are exactly the member projects`() {
        val options = cwdOptionsFor(listOf(member("sawe"), member("spk-editor-mobile")))

        assertEquals(
            listOf(
                CwdOption("sawe", "/home/u/.spk/sol/sawe"),
                CwdOption("spk-editor-mobile", "/home/u/.spk/sol/spk-editor-mobile"),
            ),
            options,
        )
    }

    @Test
    fun `solution root is never offered`() {
        val options = cwdOptionsFor(listOf(member("sawe")))

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
