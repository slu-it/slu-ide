package dev.sluide.fileexplorer.tree

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val GIT_ENTRY = ".git"

private class Entry(
    val path: Path,
) {
    // Looked up once per entry, not per comparison.
    val directory = Files.isDirectory(path)
    val name = path.fileName.toString()
}

private val entryOrder: Comparator<Entry> =
    compareBy<Entry> { !it.directory }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy { it.name }

/**
 * Lists the direct children of [directory]: folders first, then files, each sorted by name.
 *
 * Hidden files and dot-files are included; only `.git` (the repository folder, or the file pointing to it in
 * worktrees and submodules) is left out.
 * Unreadable or vanished directories yield an empty list instead of failing.
 */
internal fun listDirectory(directory: Path): List<Path> =
    try {
        Files.list(directory).use { entries ->
            entries
                .filter { it.fileName.toString() != GIT_ENTRY }
                .map(::Entry)
                .sorted(entryOrder)
                .map { it.path }
                .toList()
        }
    } catch (_: IOException) {
        emptyList()
    }
