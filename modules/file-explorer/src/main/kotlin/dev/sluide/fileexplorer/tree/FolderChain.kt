package dev.sluide.fileexplorer.tree

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** The most folders one row stands for, a safety net against very deep chains. */
internal const val MAX_CHAIN = 64

/**
 * The folders one row stands for when middle folders are compacted (ADR 0096): [first] and, as long as the last one is
 * a real folder (no link) whose only entry is a real folder other than `.git` (a nested repository ends the chain),
 * that folder; at most [MAX_CHAIN]. Just [first] for a file, a link, an unreadable folder, or one with no or several
 * entries.
 */
internal fun compactChain(first: Path): List<Path> {
    val chain = mutableListOf(first)
    while (chain.size < MAX_CHAIN) {
        val next = onlySubfolder(chain.last()) ?: break
        chain.add(next)
    }
    return chain
}

/** The only entry of [folder] if it is a real folder (not a link, not `.git`) and [folder] is one too; else `null`. */
private fun onlySubfolder(folder: Path): Path? {
    if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return null
    val entries =
        try {
            Files.newDirectoryStream(folder).use { stream -> stream.asSequence().take(2).toList() }
        } catch (_: IOException) {
            return null
        } catch (_: DirectoryIteratorException) {
            // Reading the entries failed part way (an I/O error while iterating, not reproducible in a test): the
            // folder ends the chain like an unreadable one, instead of the error reaching the JavaFX thread.
            return null
        } catch (_: SecurityException) {
            return null
        }
    val only = entries.singleOrNull() ?: return null
    return only.takeIf { it.fileName.toString() != GIT_ENTRY && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
}
