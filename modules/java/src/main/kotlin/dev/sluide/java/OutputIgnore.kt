package dev.sluide.java

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.util.UUID

/**
 * The `.gitignore` side of the offer for jdtls's compiled output (ADR 0179): where jdtls compiles to, which
 * `.gitignore` takes the lines, the lines, and writing them. Pure file logic; git decides what is ignored
 * ([OutputIgnoreChecks]).
 */
internal object OutputIgnore {
    const val FILE = ".gitignore"

    private const val MAX_ENTRIES = 10_000
    private const val MAX_DEPTH = 64
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** The folders jdtls compiles [projects] into: Gradle `build/jdtls-bin` (Thoreau's init script), Maven `target`. */
    fun outputFolders(
        tool: BuildTool,
        projects: List<Path>,
    ): List<Path> =
        projects
            .map { project ->
                when (tool) {
                    BuildTool.GRADLE -> project.resolve("build").resolve("jdtls-bin")
                    BuildTool.MAVEN -> project.resolve("target")
                }.normalize()
            }.distinct()

    /**
     * The `.gitignore` that takes the lines for [buildRoot]: the first one found walking up from it to [repository]
     * (a symbolic link counts, [add] refuses it; a folder of that name does not), else a new one in [buildRoot].
     */
    fun target(
        buildRoot: Path,
        repository: Path,
    ): Path {
        val top = repository.toAbsolutePath().normalize()
        var folder: Path? = buildRoot.toAbsolutePath().normalize()
        while (folder != null && folder.startsWith(top)) {
            val candidate = folder.resolve(FILE)
            if (Files.isSymbolicLink(candidate) || Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate
            }
            folder = folder.parent
        }
        return buildRoot.toAbsolutePath().normalize().resolve(FILE)
    }

    /**
     * Whether [folder] holds a regular file, at most [maxDepth] folders down and looking at no more than [maxEntries]
     * entries (then false): git lists files, never an empty folder, so only a file can make an offer. Links are not
     * followed. A missing or unreadable folder has none.
     */
    fun hasFile(
        folder: Path,
        maxEntries: Int = MAX_ENTRIES,
        maxDepth: Int = MAX_DEPTH,
    ): Boolean {
        var seen = 0

        fun search(
            current: Path,
            depth: Int,
        ): Boolean {
            try {
                Files.newDirectoryStream(current).use { entries ->
                    for (entry in entries) {
                        if (++seen > maxEntries) return false
                        val attributes =
                            Files.readAttributes(
                                entry,
                                BasicFileAttributes::class.java,
                                LinkOption.NOFOLLOW_LINKS,
                            )
                        if (attributes.isRegularFile) return true
                        if (attributes.isDirectory && depth < maxDepth && search(entry, depth + 1)) return true
                    }
                }
            } catch (_: IOException) {
                // Gone or unreadable: no file to be seen.
            }
            return false
        }
        // git lists a link as one entry, which does not match `folder/`: the answer would read "ignored".
        return !Files.isSymbolicLink(folder) && search(folder, 0)
    }

    /** [segment] as a gitignore pattern: a leading `#` or `!`, `*`, `?`, `[`, `\` and trailing spaces quoted. */
    fun escape(segment: String): String {
        val trimmed = segment.trimEnd(' ')
        val quoted = StringBuilder()
        trimmed.forEachIndexed { index, char ->
            if (char in "*?[\\" || (index == 0 && char in "#!")) quoted.append('\\')
            quoted.append(char)
        }
        repeat(segment.length - trimmed.length) { quoted.append("\\ ") }
        return quoted.toString()
    }

    /**
     * The line that ignores [folder] in the `.gitignore` of [ignoreFolder]: anchored, relative to it and ending in `/`.
     * Null if [folder] is not below [ignoreFolder] or a segment of its name has a line break.
     */
    fun line(
        ignoreFolder: Path,
        folder: Path,
    ): String? {
        val below = ignoreFolder.toAbsolutePath().normalize().relativize(folder.toAbsolutePath().normalize())
        if (below.toString().isEmpty() || below.startsWith("..")) return null
        val segments = below.map { it.toString() }
        if (segments.any { '\n' in it || '\r' in it }) return null
        return segments.joinToString("/", prefix = "/", postfix = "/") { escape(it) }
    }

    /**
     * Adds the lines of [folders] to the `.gitignore` [target] in [repository] (created if missing), those not yet
     * present as exactly that line. Keeps the file's line ending (CRLF if its first line ends so), a byte order mark and
     * its permissions; ends a last line without newline first. An existing file is replaced through a temporary file
     * and `ATOMIC_MOVE`, a new one is created with `CREATE_NEW`. Off the FX thread.
     *
     * @return null, or "Cannot add to <target>: <reason>" and nothing written: [target] is a symbolic link, lies under
     * a `.git` folder or not in [repository], a folder is not below its folder or has a line break in its name, or
     * the file cannot be read or written.
     */
    fun add(
        target: Path,
        repository: Path,
        folders: List<Path>,
    ): String? {
        val file = target.toAbsolutePath().normalize()
        // An absolute path with a file name always has a folder.
        val ignoreFolder = file.parent
        reasonToRefuse(file, repository)?.let { return refusal(file, it) }
        val lines =
            folders.map { folder ->
                line(ignoreFolder, folder)
                    ?: return refusal(
                        file,
                        if (folder.toString().any { it == '\n' || it == '\r' }) {
                            "$folder has a line break in its name"
                        } else {
                            "$folder is not below $ignoreFolder"
                        },
                    )
            }
        return try {
            write(file, lines)
            null
        } catch (e: IOException) {
            refusal(file, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun refusal(
        target: Path,
        reason: String,
    ) = "Cannot add to $target: $reason"

    private fun reasonToRefuse(
        file: Path,
        repository: Path,
    ): String? {
        val top = repository.toAbsolutePath().normalize()
        return when {
            inGitFolder(file.parent) -> "it is inside a .git folder"
            Files.isSymbolicLink(file) -> "it is a symbolic link"
            !insideByRealPath(file.parent, top) -> "it is not inside the repository"
            else -> null
        }
    }

    /** Whether a segment of [folder] is `.git`, also of the folder a link on the way leads to. */
    private fun inGitFolder(folder: Path): Boolean {
        val real =
            try {
                folder.toRealPath()
            } catch (_: IOException) {
                folder
            }
        return (folder.toList() + real.toList()).any { it.toString() == ".git" }
    }

    /** Whether [folder] is in [top] also when a link on the way is followed. */
    private fun insideByRealPath(
        folder: Path,
        top: Path,
    ): Boolean =
        try {
            folder.toRealPath().startsWith(top.toRealPath())
        } catch (_: IOException) {
            false
        }

    private fun write(
        file: Path,
        lines: List<String>,
    ) {
        val old = if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) Files.readAllBytes(file) else null
        // `copyOf` pads a shorter file with zeros, which are no byte order mark.
        val start = if (old != null && old.copyOf(BOM.size).contentEquals(BOM)) BOM.size else 0
        val text = old?.let { String(it, start, it.size - start, Charsets.UTF_8) }.orEmpty()
        val newline = text.indexOf('\n')
        val eol = if (newline > 0 && text[newline - 1] == '\r') "\r\n" else "\n"
        val present = text.split('\n').map { it.removeSuffix("\r") }.toSet()
        val added = lines.distinct().filter { it !in present }
        if (added.isEmpty()) return
        val ending = if (text.isNotEmpty() && !text.endsWith("\n")) eol else ""
        val bytes = (old ?: ByteArray(0)) + (ending + added.joinToString("") { it + eol }).toByteArray(Charsets.UTF_8)
        if (old == null) {
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } else {
            replace(file, bytes)
        }
    }

    /** A copy of `writeAtomically` of `local-models`, for bytes: modules never depend on each other. */
    private fun replace(
        file: Path,
        bytes: ByteArray,
    ) {
        val temp = file.resolveSibling(".${file.fileName}-${UUID.randomUUID()}.tmp")
        try {
            // Not Files.createTempFile, which creates files only the owner can read.
            Files.newByteChannel(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
            }
            if (Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
                Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS))
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Files.deleteIfExists(temp)
            throw e
        } catch (e: RuntimeException) {
            Files.deleteIfExists(temp)
            throw e
        }
    }
}
