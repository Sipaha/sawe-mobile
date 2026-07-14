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

    private fun member(id: Long) = SolutionMember(
        catalogId = id,
        localPath = "/home/u/.spk/sol/$id",
        status = "ready",
    )

    @Test
    fun `options are exactly the member projects`() {
        val options = cwdOptionsFor(listOf(member(1L), member(2L)))

        assertEquals(
            listOf(
                CwdOption("1", "/home/u/.spk/sol/1"),
                CwdOption("2", "/home/u/.spk/sol/2"),
            ),
            options,
        )
    }

    @Test
    fun `solution root is never offered`() {
        val options = cwdOptionsFor(listOf(member(1L)))

        assertTrue(options.none { it.label.contains("root", ignoreCase = true) })
        // The member's own path is the deepest thing offered — no parent dir.
        assertTrue(options.all { it.path.endsWith("/1") })
    }

    @Test
    fun `a solution with no members offers nothing`() {
        // Empty options => the dialog hides the picker and sends no `cwd`,
        // leaving the choice to the server rather than defaulting to the root.
        assertEquals(emptyList<CwdOption>(), cwdOptionsFor(emptyList()))
    }
}
