package ru.sipaha.spkremote.app.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Local crash log capture (R-6g). On-device only — no Sentry / Crashlytics
 * / Firebase / external service of any kind. The user reports a bug by
 * tapping "Share" on a crash entry inside Settings → Crash logs and picks
 * an app (mail, Telegram, Files, …) from the system chooser.
 *
 * Storage: `context.filesDir/crash-logs/crash-{yyyyMMdd-HHmmss-SSS}.log`.
 * `filesDir` is app-private (mode 0700, not world-readable on rooted
 * devices; sandboxed away from `MediaStore` / scoped storage entirely).
 * `android:allowBackup="false"` in the manifest keeps the directory out
 * of adb / Google Drive auto-backups.
 *
 * Rotation: keeps newest [MAX_CRASH_FILES] entries; older files are
 * deleted on the next install (so the directory never grows unbounded
 * if the app crash-loops). Rotation runs inside the crash handler under
 * a try/catch — *nothing* in this object is allowed to throw on top of
 * a crash, even if the disk is full or the directory is gone.
 *
 * Privacy: stack traces are passed verbatim from `Throwable.printStackTrace`.
 * The codebase doesn't currently put HMAC secrets, pairing URLs, or
 * EncryptedSharedPreferences plaintext into exception messages — verified
 * by `grep -rn "Log\." app/src core/src` (R-6g audit). If future work
 * adds such a path, scrub before `throw` or override `getLocalizedMessage()`.
 */
object CrashLogger {
    private const val MAX_CRASH_FILES = 10
    private const val DIR_NAME = "crash-logs"

    /**
     * Wire a default uncaught-exception handler that writes the crash to
     * a file under [DIR_NAME] before chaining to whatever handler was
     * installed before us (usually the platform's "kill the process"
     * default). Idempotent across multiple `install()` calls only if the
     * caller takes care; [ru.sipaha.spkremote.app.SpkApplication] only
     * calls it once from `onCreate`.
     */
    fun install(context: Context) {
        val crashDir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashFile(context, crashDir, thread, throwable)
                rotateOldFiles(crashDir)
            } catch (logFailure: Throwable) {
                // Swallow — logging must never crash on top of a crash.
                // We can't surface this anywhere: the process is about to
                // die and the user wouldn't see a Toast.
            }
            // Chain to the previous handler (typically the JVM/Android
            // default, which prints to logcat and kills the process).
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Return the crash files newest-first. Empty when the directory
     * doesn't exist yet (first launch with no crashes recorded).
     */
    fun listCrashFiles(context: Context): List<File> =
        File(context.filesDir, DIR_NAME)
            .takeIf { it.isDirectory }
            ?.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.toList()
            ?: emptyList()

    /**
     * Read a crash file as UTF-8 text. Returns an error sentinel string
     * (not `null`, not a throw) so the caller can drop the result
     * straight into a `Text` composable without an extra branch.
     */
    fun readCrashFile(file: File): String =
        runCatching { file.readText() }.getOrElse { "Failed to read crash file: ${it.message}" }

    /**
     * Delete every file under [DIR_NAME]. Subdirectories (we never
     * create any) are left alone — `listFiles()` returns both, but
     * `File.delete()` on a non-empty dir silently no-ops.
     */
    fun clearAll(context: Context) {
        File(context.filesDir, DIR_NAME)
            .takeIf { it.isDirectory }
            ?.listFiles()
            ?.forEach { it.delete() }
    }

    private fun writeCrashFile(
        context: Context,
        dir: File,
        thread: Thread,
        throwable: Throwable,
    ) {
        val now = Date()
        val timestampIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(now)
        val fileTimestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(now)
        val file = File(dir, "crash-$fileTimestamp.log")

        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionName = packageInfo?.versionName ?: "unknown"
        val versionCode = packageInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                it.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION") it.versionCode.toString()
            }
        } ?: "unknown"

        val sw = StringWriter()
        sw.append("# spk-editor-mobile crash report\n")
        sw.append("time: $timestampIso\n")
        sw.append("app:  $versionName ($versionCode)\n")
        sw.append("device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sw.append("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        sw.append("abi: ${Build.SUPPORTED_ABIS.joinToString(",")}\n")
        sw.append("thread: ${thread.name} (id=${thread.id})\n")
        sw.append("\n--- stacktrace ---\n")
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        file.writeText(sw.toString())
    }

    private fun rotateOldFiles(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_CRASH_FILES).forEach { it.delete() }
    }
}
