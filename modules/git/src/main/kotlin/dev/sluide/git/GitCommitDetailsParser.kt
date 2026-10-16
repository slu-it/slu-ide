package dev.sluide.git

import java.time.Instant

/**
 * Reads the output of `git show -s --format=%H%x1f%P%x1f%aN%x1f%aE%x1f%at%x1f%cN%x1f%cE%x1f%ct%x1f%B`: the fields of
 * one commit separated by U+001F, the raw message last. `null` if the output does not have these fields.
 */
internal object GitCommitDetailsParser {
    private val HASH = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

    fun parse(output: String): GitCommitDetails? {
        // The message is the last field, so a separator in it stays in it.
        val fields = output.split('\u001f', limit = 9)
        if (fields.size != 9) return null
        val (hash, parents, author, authorEmail, authorTime) = fields
        val authored = authorTime.toLongOrNull()
        val committed = fields[7].toLongOrNull()
        if (!HASH.matches(hash) || authored == null || committed == null) return null
        val message = fields[8].trimEnd('\r', '\n')
        return GitCommitDetails(
            commit =
                GitCommit(
                    hash = hash,
                    parents = parents.split(' ').filter(String::isNotEmpty),
                    author = author,
                    authorEmail = authorEmail,
                    authorTime = Instant.ofEpochSecond(authored),
                    subject = subject(message),
                ),
            committer = fields[5],
            committerEmail = fields[6],
            committerTime = Instant.ofEpochSecond(committed),
            message = message,
        )
    }

    /** The subject as `%s` gives it: the lines of the first paragraph, joined by spaces. */
    private fun subject(message: String): String =
        message
            .lineSequence()
            .dropWhile(String::isBlank)
            .takeWhile { it.isNotBlank() }
            .joinToString(" ") { it.trim() }
}
