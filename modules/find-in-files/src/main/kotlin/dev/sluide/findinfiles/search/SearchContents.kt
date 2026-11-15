package dev.sluide.findinfiles.search

import dev.sluide.textediting.SearchOptions
import dev.sluide.textediting.TextSearch
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** When a search stops or leaves something out (ADR 0177). */
internal data class SearchLimits(
    /** The most matches listed; the walk stops there. */
    val total: Int = 1000,
    /** The most matches listed per file; more are only counted as "more". */
    val perFile: Int = 100,
    /** Larger files (and buffers longer than this in characters) are skipped and counted. */
    val maxBytes: Int = 2 * 1024 * 1024,
    /** The most entries the walk visits; it stops there. */
    val maxVisited: Int = 200_000,
)

/**
 * What to search: the [query] with [options] in the files under [roots] that [mask] lets through. [unsaved] holds the
 * texts of dirty editors, which are searched instead of the files on disk, by `toAbsolutePath().normalize()`d path.
 */
internal data class SearchRequest(
    val roots: List<Path>,
    val query: String,
    val options: SearchOptions,
    val mask: String,
    val unsaved: Map<Path, String>,
    val limits: SearchLimits = SearchLimits(),
    /**
     * When set, the walk covers only this folder, which lies inside one of [roots]; [mask] and [FileHits.root] still
     * use that root. A scope outside every root is walked as a root of its own (defensive).
     */
    val scope: Path? = null,
)

/**
 * One line with matches; [line] 0-based, [column] of its first match in UTF-16 units; [highlights] are
 * [start, end) ranges in [snippet] (as `start until end`).
 */
internal data class LineHit(
    val line: Int,
    val column: Int,
    val snippet: String,
    val highlights: List<IntRange>,
)

/**
 * The matching lines of one [file] found under [root] (both absolute and normalised), in order. [matchCount] matches
 * are listed; [capped] when the file has more.
 */
internal data class FileHits(
    val file: Path,
    val root: Path,
    val lines: List<LineHit>,
    val matchCount: Int,
    val capped: Boolean,
)

internal sealed interface SearchOutcome {
    /**
     * The walk ended: [matches] listed in [files] files, [skipped] files too large or too slow to search;
     * [totalCapped] when it stopped at the total limit, [visitCapped] when it stopped at the visited entries.
     */
    data class Done(
        val matches: Int,
        val files: Int,
        val skipped: Int,
        val totalCapped: Boolean,
        val visitCapped: Boolean,
    ) : SearchOutcome

    /** The query is an invalid regular expression; [message] says why. Nothing was searched. */
    data class Invalid(
        val message: String,
    ) : SearchOutcome
}

/** The folder entry the walk never lists, as in the tree. */
private const val GIT_ENTRY = ".git"

/** How many visited entries pass between two checks of `cancelled` inside a folder. */
private const val CANCEL_CHECK_EVERY = 256

/** How much of a file [isTextSample][dev.sluide.textediting.isTextSample] looks at. */
internal const val TEXT_SAMPLE_BYTES = 8 * 1024

/**
 * Walks [request]'s roots (or only its scope) with [entries] (the tree's listing; may throw IOException → folder
 * skipped), calls [onFile] for every file with hits in walk order; `null` when [cancelled]. Off the FX thread.
 *
 * Depth-first in the order [entries] lists, roots in the given order. A `.git` entry is skipped, a symbolic link to a
 * folder is not followed (a link to a file is searched), anything but regular files is ignored. Every visited entry
 * counts towards [SearchLimits.maxVisited]. Files the mask leaves out are not read; binary and undecodable files are
 * skipped silently, files over [SearchLimits.maxBytes] and files whose search runs out of time are counted as skipped.
 */
internal fun searchContents(
    request: SearchRequest,
    entries: (Path) -> List<Path>,
    cancelled: () -> Boolean,
    onFile: (FileHits) -> Unit,
): SearchOutcome? {
    TextSearch.find("", request.query, request.options).error?.let { return SearchOutcome.Invalid(it) }
    val limits = request.limits
    val mask = FileMask.parse(request.mask)
    var visited = 0
    var listed = 0
    var files = 0
    var skipped = 0

    fun done(
        totalCapped: Boolean = false,
        visitCapped: Boolean = false,
    ) = SearchOutcome.Done(listed, files, skipped, totalCapped, visitCapped)
    val scope = request.scope?.toAbsolutePath()?.normalize()
    val roots = request.roots.map { it.toAbsolutePath().normalize() }
    // Each walk is (the root masks and headers use, the folder walked).
    val walks =
        scope?.let { listOf((roots.firstOrNull { scope.startsWith(it) } ?: scope) to scope) }
            ?: roots.map { it to it }
    for ((root, start) in walks) {
        val folders = ArrayDeque<Iterator<Path>>()
        readEntries(entries, start)?.let { folders.addLast(it.iterator()) }
        while (folders.isNotEmpty()) {
            val folder = folders.last()
            if (!folder.hasNext()) {
                folders.removeLast()
                continue
            }
            val entry = folder.next()
            if (visited >= limits.maxVisited) return done(visitCapped = true)
            visited++
            if (visited % CANCEL_CHECK_EVERY == 0 && cancelled()) return null
            if (entry.fileName?.toString() == GIT_ENTRY) continue
            // Not following links: a link to a folder is neither descended into nor a regular file below.
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                if (cancelled()) return null
                readEntries(entries, entry)?.let { folders.addLast(it.iterator()) }
                continue
            }
            if (!Files.isRegularFile(entry) || !mask.matches(root, entry)) continue
            if (cancelled()) return null
            val file = entry.toAbsolutePath().normalize()
            val read = readText(file, request.unsaved, limits.maxBytes)
            if (read == FileText.TooLarge) skipped++
            val text = (read as? FileText.Text)?.text ?: continue
            val found = TextSearch.find(text, request.query, request.options, limit = limits.perFile + 1)
            if (found.error != null) {
                skipped++
                continue
            }
            if (found.matches.isEmpty()) continue
            val take = minOf(limits.perFile, limits.total - listed)
            val shown = found.matches.take(take)
            files++
            listed += shown.size
            onFile(FileHits(file, root, lineHits(text, shown), shown.size, capped = found.matches.size > take))
            if (listed >= limits.total) return done(totalCapped = true)
        }
    }
    return done()
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
