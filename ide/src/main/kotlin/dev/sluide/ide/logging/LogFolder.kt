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
        val preferred =
            System.getProperty("sluide.log.dir")?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".sluide", "logs")
        val fallback = Path.of(System.getProperty("java.io.tmpdir"), "sluide-logs-${System.getProperty("user.name")}")
        val folder = logFolder(preferred, fallback)
        if (folder != preferred) addWarn("Cannot write the log file to $preferred, using $folder")
        return folder.toString()
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
