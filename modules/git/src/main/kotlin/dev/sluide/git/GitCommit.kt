package dev.sluide.git

import java.time.Instant

/**
 * One commit of the git log (ADR 0035). [author] and [authorEmail] are mapped through `.mailmap` like in the user's
 * git; [parents] are the full object names of its parents, none for a root commit, two or more for a merge.
 */
data class GitCommit(
    val hash: String,
    val parents: List<String>,
    val author: String,
    val authorEmail: String,
    val authorTime: Instant,
    /** The first line of the message (git joins a first paragraph spanning several lines into one). */
    val subject: String,
)
