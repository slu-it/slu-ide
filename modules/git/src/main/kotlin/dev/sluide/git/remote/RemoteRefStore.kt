package dev.sluide.git.remote

import dev.sluide.git.CachedCommit
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Properties
import java.util.UUID

private val log = System.getLogger(RemoteRefStore::class.java.name)

/**
 * The refs fetched into a cached bare repository, each with the commit it was fetched as and when: the properties file
 * [FILE] in the repository folder, `<ref>=<commit> <epoch milliseconds>`. Written whole to a temporary file that is
 * moved over the old one atomically, so a reader sees the old or the new content, never a part; written only under the
 * repository's lock ([RepositoryLocks]).
 */
internal object RemoteRefStore {
    const val FILE = "sluide-refs.properties"

    /** The refs of the repository in [directory]; empty if there are none or the file cannot be read. */
    fun read(directory: Path): Map<String, CachedCommit> {
        val file = directory.resolve(FILE)
        if (!Files.isRegularFile(file)) return emptyMap()
        val properties = Properties()
        try {
            Files.newBufferedReader(file).use(properties::load)
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot read $file", e)
            return emptyMap()
        } catch (e: IllegalArgumentException) {
            log.log(System.Logger.Level.WARNING, "Cannot read $file: ${e.message}")
            return emptyMap()
        }
        return properties
            .stringPropertyNames()
            .mapNotNull { ref ->
                parse(properties.getProperty(ref))?.let { ref to it }
            }.toMap()
    }

    /** Remembers that [ref] was fetched as [commit]. Throws IOException. */
    fun put(
        directory: Path,
        ref: String,
        commit: CachedCommit,
    ) {
        val properties = Properties()
        read(directory).forEach { (name, cached) -> properties.setProperty(name, format(cached)) }
        properties.setProperty(ref, format(commit))
        val temporary = directory.resolve("$FILE.${UUID.randomUUID()}.tmp")
        try {
            Files.newBufferedWriter(temporary).use { properties.store(it, null) }
            Files.move(
                temporary,
                directory.resolve(FILE),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun format(commit: CachedCommit) = "${commit.commit} ${commit.fetchedAt.toEpochMilli()}"

    private fun parse(value: String): CachedCommit? {
        val commit = value.substringBefore(' ')
        val millis = value.substringAfter(' ', "").toLongOrNull() ?: return null
        return if (isFullCommit(commit)) CachedCommit(commit, Instant.ofEpochMilli(millis)) else null
    }
}
