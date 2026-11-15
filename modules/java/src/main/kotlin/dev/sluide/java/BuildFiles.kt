package dev.sluide.java

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** What is read of one build file at most (and of one pom). */
internal const val MAX_FILE_BYTES = 1 shl 20

/** Folders below a root a walk descends into at most, and how many folders it visits before it stops. */
internal const val MAX_WALK_DEPTH = 8
internal const val MAX_WALK_FOLDERS = 20_000

private val SKIPPED_FOLDERS = setOf("node_modules", "build", "target", "bin", "out")

/** Whether a walk leaves out a folder of this [name]: hidden, a dependency folder or a build output folder. */
internal fun skipsFolder(name: String): Boolean = name.startsWith(".") || name in SKIPPED_FOLDERS

/**
 * Whether the trust checks look at [folder] (ADR 0179, as `GradleCoverage` of the gradle module): the window folder or
 * a folder below it at most [MAX_WALK_DEPTH] folders deep, with no hidden or skipped segment and no symbolic link.
 * Only such builds are imported, so what runs on an import was shown in the trust prompt.
 */
internal fun covered(
    windowFolder: Path,
    folder: Path,
): Boolean {
    val top = windowFolder.toAbsolutePath().normalize()
    val target = folder.toAbsolutePath().normalize()
    if (!target.startsWith(top)) return false
    val relative = top.relativize(target)
    if (relative.nameCount > MAX_WALK_DEPTH) return false
    var current = top
    for (segment in relative) {
        current = current.resolve(segment)
        if (skipsFolder(segment.toString()) || Files.isSymbolicLink(current)) return false
    }
    return true
}

/**
 * Visits [root] and the folders below it (at most [MAX_WALK_DEPTH] deep, no hidden or output folders, no links,
 * children in name order), calling [visit] with each folder and its children; the walk ends when [visit] returns
 * `false`. Blocks on I/O. The answer is `false` if it stopped early: past [maxFolders] folders, or the thread was interrupted. A folder that cannot be listed is skipped.
 */
internal fun walkFolders(
    root: Path,
    maxFolders: Int = MAX_WALK_FOLDERS,
    visit: (folder: Path, children: List<Path>) -> Boolean,
): Boolean {
    var visited = 0
    var complete = true
    var stopped = false

    fun walk(
        dir: Path,
        depth: Int,
    ) {
        if (Thread.currentThread().isInterrupted || ++visited > maxFolders) {
            complete = false
            return
        }
        val children =
            try {
                Files.list(dir).use { stream -> stream.sorted().toList() }
            } catch (_: IOException) {
                return
            } catch (_: UncheckedIOException) {
                return
            }
        if (!visit(dir, children)) {
            stopped = true
            return
        }
        if (depth >= MAX_WALK_DEPTH) return
        for (child in children) {
            if (stopped) return
            if (skipsFolder(child.fileName.toString())) continue
            // Not a directory by its own attributes: files, and links to folders.
            if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue
            walk(child, depth + 1)
        }
    }
    walk(root, 0)
    return complete
}

/** Up to [MAX_FILE_BYTES] + 1 bytes of [file] (one more than allowed, so callers see "bigger"); `null` if unreadable. */
internal fun readLimitedBytes(file: Path): ByteArray? =
    try {
        Files.newInputStream(file).use { it.readNBytes(MAX_FILE_BYTES + 1) }
    } catch (_: IOException) {
        null
    }

/** The first [MAX_FILE_BYTES] bytes of [file] as text; `null` if it cannot be read. */
internal fun readLimited(file: Path): String? =
    readLimitedBytes(file)?.let { String(it, 0, minOf(it.size, MAX_FILE_BYTES), Charsets.UTF_8) }
