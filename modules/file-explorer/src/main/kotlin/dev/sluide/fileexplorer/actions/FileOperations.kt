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
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** A file operation was refused or failed; the message is meant for the user. */
internal class FileOperationException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Creates, renames, moves and deletes files and folders after checking their names (ADR 0024, ADR 0073). Names are
 * single entries of a folder: no paths, and renaming never moves anything to another folder; [move] does, keeping the
 * name. Deleting is permanent.
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
    ): String? =
        when {
            name.isEmpty() -> {
                "Enter a name."
            }

            '\u0000' in name -> {
                "The name must not contain a NUL character."
            }

            name.isBlank() || name != name.trim() -> {
                "The name must not start or end with a space."
            }

            '/' in name || '\\' in name -> {
                "The name must not contain / or \\."
            }

            name == "." || name == ".." -> {
                "“$name” cannot be used as a name."
            }

            renaming != null && name == renaming.fileName.toString() -> {
                null
            }

            else -> {
                val target =
                    try {
                        folder.resolve(name)
                    } catch (_: InvalidPathException) {
                        null
                    }
                when {
                    target == null -> "The name contains characters the file system cannot store."
                    renaming != null && isSameEntry(target, renaming) -> null
                    Files.exists(target, NOFOLLOW_LINKS) -> "“$name” already exists in this folder."
                    else -> null
                }
            }
        }

    /** Creates the empty file [name] in [folder] and returns it. */
    fun createFile(
        folder: Path,
        name: String,
    ): Path = create(folder, name) { Files.createFile(it) }

    /** Creates the empty folder [name] in [folder] and returns it. */
    fun createFolder(
        folder: Path,
        name: String,
    ): Path = create(folder, name) { Files.createDirectory(it) }

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
        if (folder.startsWith(path)) throw FileOperationException("“$name” cannot be moved into itself.")
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

    private fun create(
        folder: Path,
        name: String,
        create: (Path) -> Path,
    ): Path {
        requireUsableName(folder, name, null)
        val path = folder.resolve(name)
        return failingWith("create", path) { create(path) }
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
