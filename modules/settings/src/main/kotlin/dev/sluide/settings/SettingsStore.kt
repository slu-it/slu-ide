package dev.sluide.settings

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

private val log = System.getLogger(SettingsStore::class.java.name)

/**
 * Keeps settings as key/value pairs in a properties [file], by default `~/.sluide/settings.properties`. A missing or
 * unreadable file means "no settings"; values that cannot be used are the reader's business (they fall back to
 * defaults).
 */
@Component
class SettingsStore(
    val file: Path,
) {
    @Autowired
    constructor() : this(Path.of(System.getProperty("user.home"), ".sluide", "settings.properties"))

    /** All stored values. */
    fun load(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return try {
            val properties = Properties()
            file.inputStream().use { properties.load(it) }
            properties.stringPropertyNames().associateWith { properties.getProperty(it) }
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot read settings from $file", e)
            emptyMap()
        }
    }

    /**
     * Stores [values] in addition to the values already stored, replacing those with the same keys. Writes a
     * temporary file first and moves it into place, so the file is never half written.
     */
    fun update(values: Map<String, String>) {
        val properties = Properties()
        load().forEach(properties::setProperty)
        values.forEach(properties::setProperty)
        file.parent?.createDirectories()
        val temporary = Files.createTempFile(file.parent ?: Path.of("."), file.fileName.toString(), ".tmp")
        try {
            temporary.outputStream().use { properties.store(it, "SluIde settings") }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
