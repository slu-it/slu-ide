@file:Suppress("ktlint:standard:filename")

package dev.sluide.fileexplorer.search

import dev.sluide.fileexplorer.tree.GIT_ENTRY
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class SearchResult(
    val matches: List<Path>,
    val truncated: Boolean,
)

/**
 * Walks [roots] in order, each breadth-first (shallow matches first), and collects the entries [query] matches.
 * [entries] lists a folder's entries (and may filter them); a folder it cannot read is skipped. `.git` entries (as
 * in the tree) are skipped entirely; symbolic links are never descended into, but still match by name. Stops with
 * `truncated = true` at [limit] matches or after [maxVisited] visited entries. Runs on a background thread, never the FX thread. Returns null when
 * [cancelled] became true.
 */
internal fun searchNames(
    roots: List<Path>,
    query: NameQuery,
    entries: (folder: Path) -> List<Path>,
    limit: Int = 100,
    maxVisited: Int = 200_000,
    cancelled: () -> Boolean,
): SearchResult? {
    val found = mutableListOf<Path>()
    var visited = 0
    for (root in roots) {
        val queue = ArrayDeque<Path>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            if (cancelled()) return null
            val folder = queue.removeFirst()
            val children = readEntries(entries, folder) ?: continue
            for (entry in children) {
                if (visited >= maxVisited) return SearchResult(found, truncated = true)
                visited++
                val name = entry.fileName.toString()
                if (name == GIT_ENTRY) continue
                if (query.matches(relativePath(root, entry), name)) {
                    found.add(entry)
                    if (found.size >= limit) return SearchResult(found, truncated = true)
                }
                if (isRealDirectory(entry)) queue.add(entry)
            }
        }
    }
    return SearchResult(found, truncated = false)
}

private fun readEntries(
    entries: (Path) -> List<Path>,
    folder: Path,
): List<Path>? =
    try {
        entries(folder)
    } catch (_: IOException) {
        null
    } catch (_: UncheckedIOException) {
        null
    } catch (_: SecurityException) {
        null
    }

private fun isRealDirectory(path: Path): Boolean = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)

private fun relativePath(
    root: Path,
    entry: Path,
): String = root.relativize(entry).joinToString("/") { it.toString() }
