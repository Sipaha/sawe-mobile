package ru.sipaha.sawe.app.vm

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guard for the one line that makes the attachment-staging fix (N-42) do
 * anything at all.
 *
 * `UploadManager.stagingDir` defaults to `null`, and a null directory turns
 * `ensureStagedCopy` into a no-op — so an upload keeps streaming straight
 * from the picker's `content://` URI, whose grant dies with the process, and
 * every resume after a process kill fails on a source it can no longer open.
 * The manager compiles, runs and reports success either way; the only
 * difference is whether resume works. That is why this has now been dropped
 * from the construction site twice.
 *
 * This is deliberately a wiring assertion rather than a behaviour test: the
 * behaviour lives behind a `MainViewModel` whose construction opens four
 * Android-Keystore-backed prefs files, which is not reachable from a JVM
 * unit test. Asserting on the source is the cheapest thing that fails when
 * the argument disappears again.
 */
class AttachmentStagingWiringTest {

    private fun moduleRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/kotlin/ru/sipaha/sawe/app/vm")
            if (candidate.isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "could not locate the module root from ${System.getProperty("user.dir")}",
        )
    }

    @Test
    fun `the production UploadManager is built with a staging directory`() {
        val source = File(moduleRoot(), "app/src/main/kotlin/ru/sipaha/sawe/app/vm/MainViewModel.kt")
        assertTrue(source.isFile, "MainViewModel.kt not found at ${source.absolutePath}")

        val text = source.readText()
        val construction = text.substringAfter("= UploadManager(", "")
        assertTrue(construction.isNotEmpty(), "no UploadManager( construction found")

        val args = argumentList(construction)
        assertTrue(
            "stagingDir" in args,
            "UploadManager is constructed without stagingDir — resume after process " +
                "death will fail on the lapsed content:// grant. Args were:\n$args",
        )
        assertTrue("cacheDir" in args, "stagingDir must resolve under cacheDir. Args were:\n$args")
        // Either the constant or its value — the point is that the path is
        // the one `ATTACHMENT_STAGING_DIR` names, not an ad-hoc string.
        assertTrue(
            "ATTACHMENT_STAGING_DIR" in args || "\"$ATTACHMENT_STAGING_DIR\"" in args,
            "stagingDir must use ATTACHMENT_STAGING_DIR. Args were:\n$args",
        )
    }

    /**
     * The argument list up to the call's own closing paren.
     *
     * Comments are stripped first: the wiring carries an explanation that
     * itself contains parentheses, and a naive `substringBefore(")")`
     * truncates on those instead of on the call.
     */
    private fun argumentList(afterOpenParen: String): String {
        val code = afterOpenParen.lines().joinToString("\n") { it.substringBefore("//") }
        var depth = 1
        val out = StringBuilder()
        for (c in code) {
            when (c) {
                '(' -> depth += 1
                ')' -> depth -= 1
            }
            if (depth == 0) break
            out.append(c)
        }
        return out.toString()
    }

    @Test
    fun `the staging directory name is a stable relative path`() {
        assertTrue(ATTACHMENT_STAGING_DIR.isNotBlank())
        assertTrue(
            !File(ATTACHMENT_STAGING_DIR).isAbsolute && ".." !in ATTACHMENT_STAGING_DIR,
            "staging dir must stay inside cacheDir",
        )
    }
}
