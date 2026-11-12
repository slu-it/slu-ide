package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.diffBlobs
import dev.sluide.git.parse.GitDiffParser
import dev.sluide.textediting.decodeTextLeniently
import java.nio.file.Path

/**
 * The difference between two blobs of the repository at [root] (ADR 0038), [originalObject] before and
 * [currentObject] after, `null` for a side where the file is missing (added or deleted): [GitFileDiff.Submodule] if
 * either side is one, [GitFileDiff.TooLarge] if either is above [maxSize] bytes, [GitFileDiff.Binary] if either has a
 * NUL byte in its first 8000 bytes; else both texts, decoded as the editor decodes them, with git's hunks: the whole
 * file for an added or deleted one, none for the same blob (a rename or a mode change), else `git diff` of the blobs,
 * ignoring all whitespace if [ignoreWhitespace]. [read] gives a blob's raw content. `null` if a blob cannot be read or
 * git fails.
 */
internal fun GitCli.blobDiff(
    root: Path,
    originalObject: GitObject?,
    currentObject: GitObject?,
    maxSize: Long,
    cancellation: GitCancellation? = null,
    ignoreWhitespace: Boolean = false,
    read: (GitObject) -> ByteArray?,
): GitFileDiff? {
    val objects = listOfNotNull(originalObject, currentObject)
    if (objects.any { it.submodule }) {
        return GitFileDiff.Submodule(
            originalObject?.takeIf { it.submodule }?.name,
            currentObject?.takeIf { it.submodule }?.name,
        )
    }
    if (objects.any { it.size > maxSize }) return GitFileDiff.TooLarge
    val originalBytes = originalObject?.let { read(it) ?: return null } ?: ByteArray(0)
    val currentBytes = currentObject?.let { read(it) ?: return null } ?: ByteArray(0)
    if (isBinary(originalBytes) || isBinary(currentBytes)) return GitFileDiff.Binary
    // As the editor decodes files: UTF-8 or Windows-1252 (ADR 0098).
    val originalText = decodeTextLeniently(originalBytes)
    val currentText = decodeTextLeniently(currentBytes)
    val hunks =
        when {
            originalObject == null -> {
                wholeFile(lineCount(currentText), added = true)
            }

            currentObject == null -> {
                wholeFile(lineCount(originalText), added = false)
            }

            // The same content: renamed or only its mode changed.
            originalObject.name == currentObject.name -> {
                emptyList()
            }

            else -> {
                diffBlobs(root, originalObject.name, currentObject.name, cancellation, ignoreWhitespace)
                    ?.let(GitDiffParser::parse)
                    ?: return null
            }
        }
    return GitFileDiff.Text(originalText, currentText, hunks)
}

/** One hunk of all [lines] of a file added or deleted as a whole; none for an empty file. */
internal fun wholeFile(
    lines: Int,
    added: Boolean,
): List<GitHunk> {
    if (lines == 0) return emptyList()
    val none = 0 until 0
    val all = 0 until lines
    return listOf(if (added) GitHunk(none, all) else GitHunk(all, none))
}

/** The lines of [text] as git counts them: a final line break does not start another line. */
internal fun lineCount(text: String): Int =
    if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1

/**
 * A line of `git cat-file --batch-check`: a blob (`<name> blob <size>`) or a submodule's commit (`<name> submodule`,
 * a gitlink, whose object is not in the repository).
 */
internal class GitObject(
    val name: String,
    val size: Long,
    val submodule: Boolean,
) {
    companion object {
        private val BLOB = Regex("([0-9a-f]{40}|[0-9a-f]{64}) blob (\\d+)")
        private val SUBMODULE = Regex("([0-9a-f]{40}|[0-9a-f]{64}) submodule")

        fun of(line: String): GitObject? {
            BLOB.matchEntire(line)?.let { match ->
                val size = match.groupValues[2].toLongOrNull() ?: return null
                return GitObject(match.groupValues[1], size, submodule = false)
            }
            return SUBMODULE.matchEntire(line)?.let { GitObject(it.groupValues[1], 0, submodule = true) }
        }
    }
}
