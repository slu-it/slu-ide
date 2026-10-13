package dev.sluide.fileexplorer

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val GIT_ENTRY = ".git"

private val entryOrder: Comparator<Path> =
    compareBy<Path> { !Files.isDirectory(it) }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.fileName.toString() }
        .thenBy { it.fileName.toString() }

/**
 * Lists the direct children of [directory]: folders first, then files, each sorted by name.
 *
 * Hidden files and dot-files are included; only `.git` (the repository folder, or the file pointing to it in
 * worktrees and submodules) is left out.
 * Unreadable or vanished directories yield an empty list instead of failing.
 */
fun listDirectory(directory: Path): List<Path> =
    try {
        Files.list(directory).use { entries ->
            entries.filter { it.fileName.toString() != GIT_ENTRY }.sorted(entryOrder).toList()
        }
    } catch (_: IOException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }
