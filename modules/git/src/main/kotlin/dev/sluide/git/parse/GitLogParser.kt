package dev.sluide.git.parse

import dev.sluide.git.GitCommit
import java.time.Instant

/**
 * Reads the output of `git log -z --format=%H%x1f%P%x1f%aN%x1f%aE%x1f%at%x1f%s`: one record per commit, ended by NUL,
 * with its fields separated by U+001F. Records that do not have these fields are skipped.
 */
internal object GitLogParser {
    private val HASH = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

    fun parse(output: String): List<GitCommit> =
        output.split('\u0000').mapNotNull { record ->
            // The subject is the last field, so a separator in it (should a message contain one) stays in it.
            val fields = record.split('\u001f', limit = 6)
            if (fields.size != 6) return@mapNotNull null
            val (hash, parents, author, email, time) = fields
            val seconds = time.toLongOrNull()
            if (!HASH.matches(hash) || seconds == null) return@mapNotNull null
            GitCommit(
                hash = hash,
                parents = parents.split(' ').filter(String::isNotEmpty),
                author = author,
                authorEmail = email,
                authorTime = Instant.ofEpochSecond(seconds),
                subject = fields[5],
            )
        }
}
