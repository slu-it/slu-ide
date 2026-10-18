package dev.sluide.projecttrust

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.notExists
import kotlin.io.path.outputStream

private val log = System.getLogger(TrustedProjects::class.java.name)

private const val TRUSTED = "trusted:"
private const val UNTRUSTED = "untrusted:"

/**
 * Remembers the user's trust decisions in a properties [file], by default `~/.sluide/project-trust.properties`: the key
 * is the absolute folder path, the value `trusted:` or `untrusted:` and the fingerprint of the findings decided about.
 * Problems with the file are logged, never thrown; a file that cannot be read is never replaced.
 */
@Component
class TrustedProjects(
    internal val file: Path,
) {
    @Autowired
    constructor() : this(Path.of(System.getProperty("user.home"), ".sluide", "project-trust.properties"))

    /**
     * The decision stored for [folder] about the findings with [fingerprint]: `true` trusted, `false` not trusted,
     * `null` if there is none, it was about other findings, or the file cannot be read.
     */
    fun decision(
        folder: Path,
        fingerprint: String,
    ): Boolean? =
        when (read()?.getProperty(key(folder))) {
            TRUSTED + fingerprint -> true
            UNTRUSTED + fingerprint -> false
            else -> null
        }

    /** Remembers that the user [trusted] (or not) the findings with [fingerprint] in [folder]. */
    fun remember(
        folder: Path,
        fingerprint: String,
        trusted: Boolean,
    ) = update { it.setProperty(key(folder), (if (trusted) TRUSTED else UNTRUSTED) + fingerprint) }

    private fun key(folder: Path) = folder.toAbsolutePath().normalize().toString()

    private fun update(change: (Properties) -> Unit) {
        val properties = read()
        if (properties == null) {
            log.log(System.Logger.Level.WARNING, "Not replacing the unreadable trust decisions $file")
            return
        }
        change(properties)
        try {
            file.parent?.createDirectories()
            val temporary = Files.createTempFile(file.parent ?: Path.of("."), file.fileName.toString(), ".tmp")
            try {
                temporary.outputStream().use { properties.store(it, "Thoreau project trust") }
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot save the trust decisions to $file", e)
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
        log.log(System.Logger.Level.WARNING, "Cannot read the trust decisions from $file", e)
        return null
    }
}
