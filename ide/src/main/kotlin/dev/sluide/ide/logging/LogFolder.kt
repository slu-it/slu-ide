package dev.sluide.ide.logging

import ch.qos.logback.core.PropertyDefinerBase
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.isWritable
import kotlin.io.path.notExists

/**
 * Folder of the log file for `logback-spring.xml` (ADR 0051): the system property `sluide.log.dir` (set for tests by
 * the build), otherwise `~/.sluide/logs`. Spring Boot refuses to start when Logback cannot open its file, so a folder
 * that cannot take the log file is replaced by `sluide-logs-<user>` in the temp folder (shared by all users on Linux).
 */
class LogFolder : PropertyDefinerBase() {
    override fun getPropertyValue(): String {
        val preferred = preferredLogFolder()
        val folder = logFolder(preferred, fallbackLogFolder())
        if (folder != preferred) addWarn("Cannot write the log file to $preferred, using $folder")
        chosen = folder
        return folder.toString()
    }
}

/** What [LogFolder.getPropertyValue] returned, once Logback asked; `null` without Logback (e.g. in some tests). */
@Volatile
private var chosen: Path? = null

private fun preferredLogFolder(): Path =
    System.getProperty("sluide.log.dir")?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".sluide", "logs")

private fun fallbackLogFolder(): Path =
    Path.of(System.getProperty("java.io.tmpdir"), "sluide-logs-${System.getProperty("user.name")}")

/**
 * The folder of the log file: the one Logback was given, else (no Logback) the one it would choose. Its real path is
 * looked up once, so that files are matched also if the log folder itself is (below) a symbolic link, e.g.
 * `~/.sluide`, and the watcher names them by their real path.
 */
internal val currentLogFolder: LogFolderPaths by lazy {
    LogFolderPaths(chosen ?: logFolder(preferredLogFolder(), fallbackLogFolder()))
}

/** A folder by its own path and its real path (if that could be resolved). */
internal class LogFolderPaths(
    folder: Path,
) {
    private val roots =
        listOfNotNull(
            folder.toAbsolutePath().normalize(),
            try {
                folder.toRealPath()
            } catch (_: IOException) {
                null
            },
        ).distinct()

    /**
     * Whether [changed] is non-empty and every path is in the folder: such a change is the log file growing, which must
     * not start another status refresh (its own log lines would change the file again, forever). An empty set means
     * "unknown" and is not only log files.
     */
    fun onlyLogFiles(changed: Set<Path>): Boolean =
        changed.isNotEmpty() &&
            changed.all { path ->
                val normal = path.toAbsolutePath().normalize()
                roots.any { normal.startsWith(it) }
            }
}

/** [preferred], created if missing, when it can take the log file `sluide.log`; [fallback] otherwise. */
internal fun logFolder(
    preferred: Path,
    fallback: Path,
): Path =
    try {
        preferred.createDirectories()
        val file = preferred.resolve("sluide.log")
        val usable = preferred.isWritable() && (file.notExists() || (file.isRegularFile() && file.isWritable()))
        if (usable) preferred else fallback
    } catch (_: IOException) {
        fallback
    }
