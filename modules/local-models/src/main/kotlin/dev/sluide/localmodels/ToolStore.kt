package dev.sluide.localmodels

import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties

private val log = System.getLogger(ToolStore::class.java.name)

private const val CURRENT = "current"
private const val PREVIOUS = "previous"

/** The marker of a version folder: its version and executable; a folder without it is not installed. */
internal const val TOOL_PROPERTIES = "tool.properties"

private const val MAX_POINTER_BYTES = 256L
private const val MAX_PROPERTIES_BYTES = 4096L

private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")

/** A version folder: the version, or `<version>~<n>` when a folder of that name was there already. */
private val FOLDER = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}(~[0-9]{1,4})?")

/** A staging folder of an install, as [ToolStore.staging] creates it. */
private val STAGING = Regex("\\.install-[0-9]+")

/** A temp file of a pointer write, as [writeAtomically] names it. */
private val POINTER_TEMP = Regex("\\.(current|previous)-[0-9a-f-]{36}\\.tmp")

/** Whether [version] can name a version folder (and is no pointer file's name). */
internal fun validVersion(version: String) = VERSION.matches(version) && version != CURRENT && version != PREVIOUS

/** What the pointers of a tool root name, each null when missing or not installed. */
internal data class ToolPointers(
    val current: InstalledTool?,
    val previous: InstalledTool?,
)

/**
 * The versions of one tool under [root] (ADR 0143): `<root>/<folder>/` per version with [TOOL_PROPERTIES], and the
 * pointer files `current` and `previous` holding a folder name, each written with [write] (atomically). [prune] and
 * [deleteAll] delete only what installs create: marked version folders, staging folders and pointer temp files.
 * Blocking; off the FX thread, except [read].
 */
internal class ToolStore(
    val root: Path,
    private val write: (Path, String) -> Unit = ::writeAtomically,
) {
    private data class Pointer(
        val folder: String,
        val tool: InstalledTool,
    )

    /** Reads both pointers; small reads, also on the FX thread. A `previous` on `current`'s folder counts as none. */
    fun read(): ToolPointers {
        val current = pointer(CURRENT)
        val previous = pointer(PREVIOUS)?.takeIf { it.folder != current?.folder }
        return ToolPointers(current?.tool, previous?.tool)
    }

    /** A new, empty folder for an install's download and unpacking, inside the root. */
    fun staging(): Path {
        Files.createDirectories(root)
        return Files.createTempDirectory(root, ".install-")
    }

    /** Moves the unpacked and marked [content] to a free version folder for [version] in one step; returns it. */
    fun place(
        content: Path,
        version: String,
    ): Path {
        for (n in 1..9999) {
            val folder = root.resolve(if (n == 1) version else "$version~$n")
            if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) continue
            try {
                Files.move(content, folder, StandardCopyOption.ATOMIC_MOVE)
                return folder
            } catch (_: FileAlreadyExistsException) {
                // Taken meanwhile; the next name.
            }
        }
        throw IOException("No free folder for version $version")
    }

    /** Writes the marker of [folder]: it holds an installed [version] with [executable] in it. */
    fun mark(
        folder: Path,
        version: String,
        executable: String,
    ) {
        val properties = Properties()
        properties.setProperty("version", version)
        properties.setProperty("executable", executable)
        val text = StringWriter().also { properties.store(it, "Installed by Thoreau") }.toString()
        write(folder.resolve(TOOL_PROPERTIES), text)
    }

    /**
     * Makes [folder] current. The current version becomes the previous one, unless it is the same version (a
     * reinstall keeps the previous version). `previous` is written first, so a crash in between leaves `current` on a
     * folder that is still installed; a failure of the `current` write puts `previous` back.
     */
    fun switchTo(folder: Path) {
        val version = pointerTool(folder.name())?.version ?: throw IOException("$folder is not installed")
        val current = pointer(CURRENT)
        val previous = pointer(PREVIOUS)
        val newPrevious =
            listOfNotNull(current, previous).firstOrNull { it.tool.version != version && it.folder != folder.name() }
        val oldPrevious = raw(PREVIOUS)
        setPointer(PREVIOUS, newPrevious?.folder)
        try {
            setPointer(CURRENT, folder.name())
        } catch (e: IOException) {
            try {
                setPointer(PREVIOUS, oldPrevious)
            } catch (restore: IOException) {
                e.addSuppressed(restore)
            }
            throw e
        }
    }

    /** Swaps `current` and `previous`; false when there is no installed previous version. */
    fun rollback(): Boolean {
        val current = pointer(CURRENT)
        val previous = pointer(PREVIOUS)?.takeIf { it.folder != current?.folder } ?: return false
        setPointer(PREVIOUS, current?.folder)
        setPointer(CURRENT, previous.folder)
        return true
    }

    /**
     * Deletes the version folders the pointers do not name (by their text, so also a named folder that does not read
     * as installed right now), staging folders and pointer temp files that a crash left. Nothing else.
     */
    fun prune() = deleteOwn(listOfNotNull(raw(CURRENT), raw(PREVIOUS)).map { it.trim() }.toSet())

    /**
     * Deletes the pointers first (so a failure halfway reads as not installed), then every version folder, staging
     * folder and pointer temp file, then the root if nothing else is in it.
     */
    fun deleteAll() {
        Files.deleteIfExists(root.resolve(CURRENT))
        Files.deleteIfExists(root.resolve(PREVIOUS))
        deleteOwn(emptySet())
        try {
            Files.deleteIfExists(root)
        } catch (_: DirectoryNotEmptyException) {
            // Something no install put there: it stays, and the root with it.
        }
    }

    private fun deleteOwn(kept: Set<String>) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return
        val doomed = Files.list(root).use { entries -> entries.filter { it.name() !in kept && own(it) }.toList() }
        doomed.forEach(::deleteTree)
    }

    /** Whether an install created [entry]: a marked version folder, a staging folder or a pointer temp file. */
    private fun own(entry: Path): Boolean {
        val name = entry.name()
        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
            val marked = Files.isRegularFile(entry.resolve(TOOL_PROPERTIES), LinkOption.NOFOLLOW_LINKS)
            return STAGING.matches(name) || (FOLDER.matches(name) && marked)
        }
        return POINTER_TEMP.matches(name) && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
    }

    private fun pointer(name: String): Pointer? {
        val folder = raw(name)?.trim() ?: return null
        if (!FOLDER.matches(folder)) return null
        return pointerTool(folder)?.let { Pointer(folder, it) }
    }

    /** The tool installed in the version folder [folder], or null when it is missing, unmarked or not executable. */
    private fun pointerTool(folder: String): InstalledTool? {
        val dir = root.resolve(folder)
        val marker = dir.resolve(TOOL_PROPERTIES)
        return try {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return null
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return null
            if (Files.size(marker) > MAX_PROPERTIES_BYTES) return null
            val properties = Properties().apply { load(StringReader(Files.readString(marker))) }
            val version = properties.getProperty("version")?.takeIf(::validVersion) ?: return null
            val path = properties.getProperty("executable")?.let(::entryPath) ?: return null
            val executable = dir.resolve(path).toAbsolutePath()
            val usable = Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(executable)
            if (usable) InstalledTool(version, executable) else null
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot read $marker: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            log.log(System.Logger.Level.WARNING, "Cannot read $marker: ${e.message}")
            null
        }
    }

    /** The text of pointer file [name], or null when it is missing or too large to be one. */
    private fun raw(name: String): String? {
        val file = root.resolve(name)
        return try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_POINTER_BYTES) {
                null
            } else {
                Files.readString(file)
            }
        } catch (_: NoSuchFileException) {
            null
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot read $file: ${e.message}")
            null
        }
    }

    private fun setPointer(
        name: String,
        folder: String?,
    ) {
        if (folder == null) Files.deleteIfExists(root.resolve(name)) else write(root.resolve(name), folder)
    }

    private fun Path.name() = fileName.toString()
}

/** Deletes [path] and, for a folder, everything in it; links are deleted, never followed. Nothing when missing. */
internal fun deleteTree(path: Path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        path,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                dir: Path,
                exc: IOException?,
            ): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}
