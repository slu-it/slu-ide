package dev.sluide.projects

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.notExists
import kotlin.io.path.outputStream

private val log = System.getLogger(RecentProjects::class.java.name)

/**
 * Remembers the opened project folders in a properties [file], by default `~/.sluide/projects.properties`: the key is
 * the absolute folder path, the value the epoch milliseconds of the last time it was opened. Problems with the file are
 * logged, never thrown; a file that cannot be read is never replaced.
 */
@Component
class RecentProjects(
    internal val file: Path,
) {
    @Autowired
    constructor() : this(Path.of(System.getProperty("user.home"), ".sluide", "projects.properties"))

    /** The remembered projects, newest first; none if the file is missing or cannot be read. */
    fun list(): List<RecentProject> {
        val properties = read() ?: return emptyList()
        return properties
            .stringPropertyNames()
            .mapNotNull { key ->
                val millis = properties.getProperty(key).trim().toLongOrNull() ?: return@mapNotNull null
                val folder = runCatching { Path.of(key) }.getOrNull() ?: return@mapNotNull null
                RecentProject(folder, Instant.ofEpochMilli(millis))
            }.sortedByDescending { it.lastOpened }
    }

    /** Remembers [folder] as opened at [at]. */
    fun opened(
        folder: Path,
        at: Instant = Instant.now(),
    ) = update { it.setProperty(key(folder), at.toEpochMilli().toString()) }

    /** Forgets [folder]; the folder itself is never touched. */
    fun remove(folder: Path) = update { it.remove(key(folder)) }

    private fun key(folder: Path) = folder.toAbsolutePath().normalize().toString()

    private fun update(change: (Properties) -> Unit) {
        val properties = read()
        if (properties == null) {
            log.log(System.Logger.Level.WARNING, "Not replacing the unreadable project list $file")
            return
        }
        change(properties)
        try {
            file.parent?.createDirectories()
            val temporary = Files.createTempFile(file.parent ?: Path.of("."), file.fileName.toString(), ".tmp")
            try {
                temporary.outputStream().use { properties.store(it, "Thoreau recent projects") }
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot save the project list to $file", e)
        }
    }

    /** The stored entries: empty if there is no file, `null` if it cannot be read. */
    private fun read(): Properties? {
        val properties = Properties()
        if (file.notExists()) return properties
        return try {
            file.inputStream().use { properties.load(it) }
            properties
        } catch (e: IOException) {
            unreadable(e)
        } catch (e: IllegalArgumentException) {
            // a malformed \uXXXX escape
            unreadable(e)
        }
    }

    private fun unreadable(e: Exception): Properties? {
        log.log(System.Logger.Level.WARNING, "Cannot read the project list from $file", e)
        return null
    }
}
