package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.diffFiles
import dev.sluide.git.parse.GitDiffParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes

/** The size limit (5 MB) above which a file is not diffed. */
internal const val MAX_BLOB_SIZE = 5L * 1024 * 1024

private const val BINARY_SAMPLE = 8000

/** Git's own test for binary files: a NUL byte in the first 8000 bytes. */
internal fun isBinary(bytes: ByteArray): Boolean {
    for (index in 0 until minOf(bytes.size, BINARY_SAMPLE)) if (bytes[index] == 0.toByte()) return true
    return false
}

/**
 * Git's hunks from [original] to [current]: both written to temporary files with plain names and compared as they
 * are with `git diff --no-index` (ADR 0044), so no repository path goes on the command line, with the diff
 * [algorithm] (`null`: git's own default or the user's configuration); `null` on failure. The temporary folder, below
 * [temporaryFolder] or the system's, is deleted afterwards.
 */
internal fun GitCli.diffTexts(
    original: ByteArray,
    current: ByteArray,
    algorithm: String?,
    temporaryFolder: Path?,
): List<GitHunk>? {
    val folder =
        try {
            temporaryFolder?.let { Files.createTempDirectory(it, "sluide-diff") }
                ?: Files.createTempDirectory("sluide-diff")
        } catch (_: IOException) {
            return null
        }
    val files = listOf("original", "current", "attributes").map(folder::resolve)
    return try {
        files[0].writeBytes(original)
        files[1].writeBytes(current)
        diffFiles(folder, "original", "current", algorithm)?.let(GitDiffParser::parse)
    } catch (_: IOException) {
        null
    } finally {
        try {
            files.forEach { it.deleteIfExists() }
            folder.deleteIfExists()
        } catch (_: IOException) {
            // Left in the temporary folder.
        }
    }
}
