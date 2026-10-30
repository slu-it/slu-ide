package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.textediting.decodeTextLeniently
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compares two arbitrary files on disk with git's `--no-index` diff (ADR 0044), for a side-by-side view of any two
 * files. Runs git on the calling thread. The files are compared as raw bytes, outside any repository's attributes or
 * configuration (the isolation of `GitCli.diffFiles`); the user's global `diff.algorithm` still applies, since no
 * algorithm is passed.
 */
@Component
class GitFileComparison(
    private val git: GitCli,
) {
    /** Where the temporary files of a comparison go; `null` for the JVM's temporary folder. */
    internal var temporaryFolder: Path? = null

    /**
     * The difference from [left] to [right]: [GitFileDiff.Text] with both texts (an empty hunk list for identical
     * files), [GitFileDiff.Binary] or [GitFileDiff.TooLarge] (a file above 5 MB); `null` if either is not a regular
     * file (symbolic links are followed), cannot be read, or git is not available or fails.
     */
    fun compare(
        left: Path,
        right: Path,
    ): GitFileDiff? = compare(left, right, MAX_BLOB_SIZE)

    internal fun compare(
        left: Path,
        right: Path,
        maxSize: Long,
    ): GitFileDiff? {
        try {
            if (!Files.isRegularFile(left) || !Files.isRegularFile(right)) return null
            val leftBytes = readBounded(left, maxSize)
            if (leftBytes.size > maxSize) return GitFileDiff.TooLarge
            val rightBytes = readBounded(right, maxSize)
            if (rightBytes.size > maxSize) return GitFileDiff.TooLarge
            if (isBinary(leftBytes) || isBinary(rightBytes)) return GitFileDiff.Binary
            val hunks =
                if (leftBytes.contentEquals(rightBytes)) {
                    emptyList()
                } else {
                    git.diffTexts(leftBytes, rightBytes, algorithm = null, temporaryFolder) ?: return null
                }
            return GitFileDiff.Text(decodeTextLeniently(leftBytes), decodeTextLeniently(rightBytes), hunks)
        } catch (_: IOException) {
            return null
        }
    }

    /** At most [maxSize] + 1 bytes of [path], so that one more than the limit shows the file is too large. */
    private fun readBounded(
        path: Path,
        maxSize: Long,
    ): ByteArray = Files.newInputStream(path).use { it.readNBytes(maxSize.toInt() + 1) }
}
