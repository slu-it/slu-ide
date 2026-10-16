package dev.sluide.git

import java.time.Instant

/**
 * Everything the git log's details pane shows about a [commit] (ADR 0037) besides its refs: who committed it and
 * when, and its full [message] without trailing newlines. [committer] and [committerEmail] are mapped through
 * `.mailmap` like the author. Trailing line breaks (also CRLF) are not part of the [message].
 */
data class GitCommitDetails(
    val commit: GitCommit,
    val committer: String,
    val committerEmail: String,
    val committerTime: Instant,
    val message: String,
)
