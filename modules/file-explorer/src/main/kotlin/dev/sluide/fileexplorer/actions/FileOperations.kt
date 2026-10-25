package dev.sluide.fileexplorer.actions

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.util.UUID

/** A file operation was refused or failed; the message is meant for the user. */
internal class FileOperationException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Creates, renames, moves, copies and deletes files and folders after checking their names (ADR 0024, ADR 0073,
 * ADR 0092, ADR 0094). Names are single entries of a folder, only new entries may be given as a path
 * ([createPath]); renaming never moves anything to another folder; [move] does, keeping the name. Deleting is
 * permanent.
 */
internal object FileOperations {
    /**
     * Why [name] cannot be given to a new entry of [folder], or to [renaming] (an entry of [folder]); `null` if it can.
     * A name taken by another entry counts as taken also if it differs only in case, since that is the same entry on
     * case-insensitive file systems. [renaming] may keep its name or change only its case.
     */
    fun nameProblem(
        name: String,
        folder: Path,
        renaming: Path? = null,
    ): String? {
        syntaxProblem(name)?.let { return it }
        if (renaming != null && name == renaming.fileName.toString()) return null
        val target = resolveOrNull(folder, name) ?: return "The name contains characters the file system cannot store."
        return when {
            renaming != null && isSameEntry(target, renaming) -> null
            Files.exists(target, NOFOLLOW_LINKS) -> "“$name” already exists in this folder."
            else -> null
        }
    }

    private fun syntaxProblem(name: String): String? =
        when {
            name.isEmpty() -> "Enter a name."
            '\u0000' in name -> "The name must not contain a NUL character."
            name.isBlank() || name != name.trim() -> "The name must not start or end with a space."
            '/' in name || '\\' in name -> "The name must not contain / or \\."
            name == "." || name == ".." -> "“$name” cannot be used as a name."
            else -> null
        }

    private fun resolveOrNull(
        folder: Path,
        name: String,
    ): Path? =
        try {
            folder.resolve(name)
        } catch (_: InvalidPathException) {
            null
        }

    /**
     * Why [path], `/`-separated (also `\` if [windows]), cannot be created in [folder]; `null` if it can (ADR 0094).
     * The folders on the way may exist (as folders, links to folders too) or be created; the last part must be free.
     * A trailing `/` is allowed. A single name is checked by [nameProblem].
     */
    fun pathProblem(
        path: String,
        folder: Path,
        windows: Boolean = IS_WINDOWS,
    ): String? {
        val text = if (windows) path.replace('\\', '/') else path
        if (windows && ':' in text.substringBefore('/')) {
            return "“${text.substringBefore('/')}”: The name must not contain :."
        }
        if ('/' !in text) return nameProblem(text, folder)
        if (text.startsWith("/")) return "The path must be relative: it must not start with /."
        val parts = text.removeSuffix("/").split('/')
        if (parts.any { it.isEmpty() }) return "The path must not contain an empty part (//)."
        var current = folder
        var missing = false
        for ((index, part) in parts.withIndex()) {
            syntaxProblem(part)?.let { return if (part.isBlank() || part != part.trim()) "“$part”: $it" else it }
            // A drive such as D: would leave the folder.
            if (windows && ':' in part) return "“$part”: The name must not contain :."
            current = resolveOrNull(current, part) ?: return "“$part” contains characters the file system cannot store."
            if (!current.normalize().startsWith(folder.normalize())) return "The path must stay inside the folder."
            val last = index == parts.lastIndex
            if (missing || !Files.exists(current, NOFOLLOW_LINKS)) {
                missing = true
            } else if (last) {
                return "“${parts.joinToString("/")}” already exists in this folder."
            } else if (!Files.isDirectory(current)) {
                return "“${parts.take(index + 1).joinToString("/")}” exists and is not a folder."
            }
        }
        return null
    }

    /**
     * Creates the folders of [path] (as for [pathProblem]) in [folder] top-down, then the empty file, or a folder if
     * [isFolder] or [path] ends with `/`, and returns the last one. A folder on the way that exists is taken with its
     * name on disk, so a path differing in case (case-insensitive file systems) still leads to the created entry.
     * If it fails part way, what was created stays.
     */
    fun createPath(
        folder: Path,
        path: String,
        isFolder: Boolean,
        windows: Boolean = IS_WINDOWS,
    ): Path {
        pathProblem(path, folder, windows)?.let { throw FileOperationException(it) }
        val text = if (windows) path.replace('\\', '/') else path
        val parts = text.removeSuffix("/").split('/')
        val lastIsFolder = isFolder || text.endsWith("/")
        var current = folder
        for ((index, part) in parts.withIndex()) {
            val last = index == parts.lastIndex
            current = current.resolve(if (last) part else nameOnDisk(current, part))
            if (last && !lastIsFolder) {
                failingWith("create", current) { Files.createFile(current) }
            } else if (!Files.isDirectory(current)) {
                failingWith("create", current) {
                    try {
                        Files.createDirectory(current)
                    } catch (e: FileAlreadyExistsException) {
                        // Covers a race (made meanwhile by another process); fine if it is a folder now.
                        // Untested: it needs the race.
                        if (!Files.isDirectory(current)) throw e
                        current
                    }
                }
            }
        }
        return current
    }

    /**
     * Renames [path] to [newName] in the same folder and returns the new path. A folder keeps its content; a symbolic
     * link is renamed, not its target. Changing only the case of the name works on case-insensitive file systems, too.
     */
    fun rename(
        path: Path,
        newName: String,
    ): Path {
        val folder = checkNotNull(path.parent) { "$path has no folder" }
        requireUsableName(folder, newName, path)
        if (newName == path.fileName.toString()) return path
        val target = path.resolveSibling(newName)
        return failingWith("rename", path) {
            // Only the case changes: the file system takes the target for the file itself, and Files.move does nothing
            // then. A detour over a temporary name renames it for real.
            if (isSameEntry(target, path)) renameOverTemporary(path, target) else Files.move(path, target)
        }
    }

    /**
     * Renames [path] to [target] over a temporary name in the same folder, moving with [move]. If the second step
     * fails, the entry is moved back; if that fails too, the message says where it was left.
     */
    internal fun renameOverTemporary(
        path: Path,
        target: Path,
        move: (from: Path, to: Path) -> Unit = { from, to -> Files.move(from, to) },
    ): Path {
        val temporary = path.resolveSibling(".${path.fileName}.${UUID.randomUUID()}.renaming")
        move(path, temporary)
        try {
            move(temporary, target)
        } catch (e: IOException) {
            try {
                move(temporary, path)
            } catch (rollback: IOException) {
                e.addSuppressed(rollback)
                throw FileOperationException(
                    "Could not rename “${path.fileName}”, and it was left as $temporary: ${reason(e)}",
                    e,
                )
            }
            throw e
        }
        return target
    }

    /**
     * Moves [path] into [folder], keeping its name, and returns the new path; [path] itself if it is in [folder]
     * already. A folder keeps its content; a symbolic link is moved, not its target. Refused if [folder] is [path] or
     * lies inside it, or if the name exists in [folder] (also in another case on case-insensitive file systems):
     * nothing is ever replaced. Moves are renames only: nothing is moved to another file store (drive), where
     * `Files.move` would copy and delete.
     */
    fun move(
        path: Path,
        folder: Path,
    ): Path {
        val name = path.fileName.toString()
        if (path.parent == folder) return path
        if (isAtOrBelow(folder, path)) throw FileOperationException("“$name” cannot be moved into itself.")
        val target = folder.resolve(name)
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            throw FileOperationException("“$name” already exists in “${folder.fileName ?: folder}”.")
        }
        if (!onSameFileStore(path, folder)) throw FileOperationException(otherDrive(name))
        return failingWith("move", path) {
            try {
                Files.move(path, target)
            } catch (e: DirectoryNotEmptyException) {
                // Without replacing, only another file store refuses like this, should the check above have missed it.
                throw FileOperationException(otherDrive(name), e)
            }
        }
    }

    /**
     * Whether [path] (by its folder, so a link counts where it is, not where it points) and [folder] are on the same
     * file store; `true` if that cannot be told, leaving it to the move.
     */
    private fun onSameFileStore(
        path: Path,
        folder: Path,
    ): Boolean =
        try {
            Files.getFileStore(checkNotNull(path.parent) { "$path has no folder" }) == Files.getFileStore(folder)
        } catch (_: IOException) {
            true
        }

    private fun otherDrive(name: String) = "Could not move “$name”: it cannot be moved to another drive."

    /**
     * The name for a copy of the entry [name] (a folder if [isFolder]) in a folder where [taken] says which names are
     * in use: [name] itself if it is free, else the first free of `-copy`, `-copy2`, `-copy3`, … added to its base name
     * (ADR 0092). The base name ends before the first extension; a leading dot does not start one, and a folder has
     * none (`archive.tar.gz` → `archive-copy.tar.gz`, `.env.local` → `.env-copy.local`, `my.dir` → `my.dir-copy`). A
     * base name ending in `-copy` or `-copyN` (N ≥ 2) is the name of a copy already: its copies count on from `-copy2`
     * (`notes-copy.txt` → `notes-copy2.txt`).
     */
    fun copyName(
        name: String,
        isFolder: Boolean,
        taken: (String) -> Boolean,
    ): String {
        if (!taken(name)) return name
        val end = if (isFolder) name.length else name.indexOf('.', 1).takeIf { it > 0 } ?: name.length
        val base = name.take(end)
        val extension = name.drop(end)
        val copy = COPY_SUFFIX.matchEntire(base)
        val stem = copy?.groupValues?.get(1) ?: base
        val first = if (copy == null) 1 else 2
        return generateSequence(first) { it + 1 }
            .map { number -> stem + COPY + (if (number == 1) "" else number.toString()) + extension }
            .first { !taken(it) }
    }

    /**
     * Copies [source] into [folder] and returns the copy: named as [source] if that name is free in [folder], else as
     * [copyName] says, also when [folder] holds [source] itself. Nothing is ever replaced: the copy is created only if
     * its name is still free, else the next name is tried. A folder is copied with everything in it; symbolic links
     * are copied as links, never followed. Permissions (e.g. the executable bit) and modification times are kept. Copies
     * to another file store (drive) work. Refused if [folder] is the folder [source] or lies inside it. Stops at the
     * first entry that cannot be copied, leaving what was copied.
     */
    fun copy(
        source: Path,
        folder: Path,
    ): Path {
        val name = source.fileName.toString()
        val isFolder = Files.isDirectory(source, NOFOLLOW_LINKS)
        if (isFolder && isAtOrBelow(folder, source)) throw FileOperationException(copyIntoItself(name))
        if (!Files.exists(source, NOFOLLOW_LINKS)) {
            throw FileOperationException("Could not copy “$name”: $source does not exist.")
        }
        val target = failingWith("copy", source) { createCopy(source, folder, name, isFolder) }
        if (isFolder) failingWith("copy", source) { copyContent(source, target) }
        return target
    }

    /**
     * Whether [folder] is the entry [entry] or lies inside it, also when either is reached through a symbolic link
     * (`current` → `releases/v1` lies inside `releases`; `/var` is `/private/var` on macOS). Compared as real paths,
     * with [entry] itself not followed (a link is an entry of its own); as written if they cannot be resolved.
     */
    fun isAtOrBelow(
        folder: Path,
        entry: Path,
    ): Boolean {
        if (folder.startsWith(entry)) return true
        val parent = entry.parent ?: return false
        return try {
            folder.toRealPath().startsWith(parent.toRealPath().resolve(entry.fileName))
        } catch (_: IOException) {
            false
        }
    }

    /** The message why the folder [name] cannot be copied into itself or a folder inside it. */
    fun copyIntoItself(name: String) = "“$name” cannot be copied into itself."

    /**
     * Creates the copy of [source] in [folder] under the first free name: the file or link with its attributes, a
     * folder empty. A name taken meanwhile makes it try the next one.
     */
    private fun createCopy(
        source: Path,
        folder: Path,
        name: String,
        isFolder: Boolean,
    ): Path {
        var attempt = 0
        while (true) {
            val target = folder.resolve(copyName(name, isFolder, takenIn(folder)))
            try {
                // Both fail if the target exists, checked by the file system as it creates it (exclusive create).
                if (isFolder) {
                    Files.createDirectory(target)
                } else {
                    Files.copy(source, target, NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                }
                return target
            } catch (e: FileAlreadyExistsException) {
                if (++attempt >= MAX_COPY_ATTEMPTS) throw e
            }
        }
    }

    /**
     * Which names are taken in [folder]: those of its entries in any case, so no copy differs from an entry only in
     * case, as on case-insensitive file systems; also those the file system finds (a dangling link counts).
     */
    private fun takenIn(folder: Path): (String) -> Boolean {
        val names = Files.list(folder).use { entries -> entries.map { it.fileName.toString().lowercase() }.toList() }
        return { it.lowercase() in names || Files.exists(folder.resolve(it), NOFOLLOW_LINKS) }
    }

    /**
     * Copies everything in the folder [source] into the new, empty folder [target]. Folders get the permissions and
     * modification times of theirs once their content is copied, so a read-only folder can be filled first.
     */
    private fun copyContent(
        source: Path,
        target: Path,
    ) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (directory == source) return FileVisitResult.CONTINUE
                    // Should the copy lie inside the folder after all, it is not copied into itself again and again.
                    if (Files.isSameFile(directory, target)) return FileVisitResult.SKIP_SUBTREE
                    Files.createDirectory(target.resolve(source.relativize(directory)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    Files.copy(
                        file,
                        target.resolve(source.relativize(file)),
                        NOFOLLOW_LINKS,
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    exception: IOException?,
                ): FileVisitResult {
                    if (exception != null) throw exception
                    copyFolderAttributes(directory, target.resolve(source.relativize(directory)))
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /** Gives the folder [copy] the permissions (where the file system has them) and modification time of [folder]. */
    private fun copyFolderAttributes(
        folder: Path,
        copy: Path,
    ) {
        Files.getFileAttributeView(folder, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)?.let { view ->
            Files.setPosixFilePermissions(copy, view.readAttributes().permissions())
        }
        Files.setLastModifiedTime(copy, Files.getLastModifiedTime(folder, NOFOLLOW_LINKS))
    }

    /**
     * Deletes [path] permanently, a folder with everything in it. Symbolic links are deleted themselves, their
     * targets are left alone. Stops at the first entry that cannot be deleted, leaving the rest.
     */
    fun delete(path: Path) {
        failingWith("delete", path) {
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        directory: Path,
                        exception: IOException?,
                    ): FileVisitResult {
                        if (exception != null) throw exception
                        Files.delete(directory)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }

    /** The name of the entry [name] of [folder] as stored, if it exists in another case; [name] otherwise. */
    private fun nameOnDisk(
        folder: Path,
        name: String,
    ): String {
        if (!Files.exists(folder.resolve(name), NOFOLLOW_LINKS)) return name
        val names =
            try {
                Files.newDirectoryStream(folder).use { entries -> entries.map { it.fileName.toString() } }
            } catch (_: IOException) {
                // Not listable (e.g. only traversable): the name as typed is the best guess.
                return name
            }
        return names.firstOrNull { it == name } ?: names.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
    }

    private fun requireUsableName(
        folder: Path,
        name: String,
        renaming: Path?,
    ) {
        nameProblem(name, folder, renaming)?.let { throw FileOperationException(it) }
    }

    /**
     * Whether [candidate] names the entry [path] in another case, as on case-insensitive file systems: it leads to the
     * same file, but the folder has no entry of that exact name (which a hard link would be).
     */
    private fun isSameEntry(
        candidate: Path,
        path: Path,
    ): Boolean {
        val name = candidate.fileName.toString()
        if (!name.equals(path.fileName.toString(), ignoreCase = true)) return false
        return try {
            val key = Files.readAttributes(candidate, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
            key != null &&
                key == Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() &&
                Files.list(candidate.parent).use { entries -> entries.noneMatch { it.fileName.toString() == name } }
        } catch (_: IOException) {
            false
        }
    }

    private fun <T> failingWith(
        action: String,
        path: Path,
        operation: () -> T,
    ): T =
        try {
            operation()
        } catch (e: FileOperationException) {
            throw e
        } catch (e: IOException) {
            throw FileOperationException("Could not $action “${path.fileName}”: ${reason(e)}", e)
        }

    private fun reason(e: IOException): String =
        when (e) {
            is NoSuchFileException -> "${e.file} does not exist."
            is FileAlreadyExistsException -> "${e.file} already exists."
            is AccessDeniedException -> "permission denied for ${e.file}."
            is FileSystemException -> listOfNotNull(e.file, e.reason).joinToString(": ")
            else -> e.message ?: e.javaClass.simpleName
        }
}

private val IS_WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

private const val COPY = "-copy"

/** A base name that is the name of a copy: the stem, then `-copy` or `-copyN` with N ≥ 2 and no leading zero. */
private val COPY_SUFFIX = Regex("(.*)-copy([2-9]|[1-9][0-9]+)?")

/** How often a copy tries the next free name when the one it picked was taken meanwhile. */
private const val MAX_COPY_ATTEMPTS = 100
