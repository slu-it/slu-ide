package dev.sluide.scratches

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val log = System.getLogger(ScratchFiles::class.java.name)

/** A kind of scratch file offered by the picker: its shown [name] and the [extension] of new files (without dot). */
data class ScratchType(
    val name: String,
    val extension: String,
)

/** The types the picker offers, in this order. */
val SCRATCH_TYPES: List<ScratchType> =
    listOf(
        ScratchType("Plain Text", "txt"),
        ScratchType("Markdown", "md"),
        ScratchType("JSON", "json"),
        ScratchType("YAML", "yaml"),
        ScratchType("XML", "xml"),
        ScratchType("HTML", "html"),
        ScratchType("CSS", "css"),
        ScratchType("JavaScript", "js"),
        ScratchType("TypeScript", "ts"),
        ScratchType("Kotlin", "kt"),
        ScratchType("Kotlin Script", "kts"),
        ScratchType("Java", "java"),
        ScratchType("Python", "py"),
        ScratchType("Shell Script", "sh"),
        ScratchType("SQL", "sql"),
        ScratchType("HTTP Request", "http"),
        ScratchType("TOML", "toml"),
        ScratchType("Properties", "properties"),
        ScratchType("CSV", "csv"),
        ScratchType("Mermaid", "mmd"),
        ScratchType("KQL", "kql"),
        ScratchType("Bicep", "bicep"),
    )

/**
 * The scratch files in one folder shared by all project windows (`~/.sluide/scratches`): new files named by the lowest
 * free number, and the deletion of empty ones. Plain file IO on the calling thread; the files are tiny.
 */
@Component
class ScratchFiles(
    folder: Path,
) {
    @Autowired
    constructor() : this(Path.of(System.getProperty("user.home"), ".sluide", "scratches"))

    /** The scratches folder, absolute and normalized. */
    val folder: Path = folder.toAbsolutePath().normalize()

    /** The type chosen last in this run of the app, preselected by the picker; in memory only. */
    @Volatile
    var lastType: ScratchType? = null

    /** Creates the folder (and parents) if missing. Throws IOException. */
    fun ensureFolder() {
        Files.createDirectories(folder)
    }

    /**
     * Creates an empty new file `scratch.<ext>`, else `scratch_<n>.<ext>` with the lowest free n >= 1, with CREATE_NEW;
     * a name taken meanwhile (another window was faster, or a case-insensitive twin) tries the next. Sets [lastType].
     */
    fun create(type: ScratchType): Path {
        ensureFolder()
        var number = 0
        while (true) {
            val name = if (number == 0) "scratch.${type.extension}" else "scratch_$number.${type.extension}"
            val path = folder.resolve(name)
            try {
                Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close()
            } catch (_: FileAlreadyExistsException) {
                number++
                continue
            }
            lastType = type
            return path
        }
    }

    /** Whether [path] (made absolute and normalized) is strictly below [folder]; false for the folder itself. */
    fun contains(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize()
        return normalized != folder && normalized.startsWith(folder)
    }

    /** Deletes [path] if it is contained, a regular file (links not followed) and empty; says whether it did. */
    fun deleteIfEmpty(path: Path): Boolean {
        if (!contains(path)) return false
        val file = path.toAbsolutePath().normalize()
        return try {
            // A symlinked subfolder must not lead out of the folder.
            file.parent.toRealPath().startsWith(folder.toRealPath()) &&
                Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
                Files.size(file) == 0L &&
                Files.deleteIfExists(file)
        } catch (_: NoSuchFileException) {
            false
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot delete the empty scratch file $file", e)
            false
        }
    }
}
