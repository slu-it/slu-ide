package dev.sluide.git

/**
 * Where a push of a branch goes by default (ADR 0049): a [remote] and a [branch] on it, both short names, and whether
 * the pushed branch has an upstream ([hasUpstream]: `branch.<b>.merge` is set), so a push sets one if it has none.
 */
data class GitPushTarget(
    val remote: String,
    val branch: String,
    val hasUpstream: Boolean = false,
)

/**
 * What a push would send (ADR 0049): the [commits] of `HEAD` that the target does not have, newest first and at most
 * the asked limit ([more] says there are others); the commit the target's remote-tracking ref has ([remoteCommit],
 * `null` for a branch the remote does not have yet); how many commits that ref has that `HEAD` has not ([behind]: a
 * push without force would be rejected); and the commit to diff the whole push against ([base]): the merge base of
 * `HEAD` and the ref, for a new branch the first parent of the oldest outgoing commit; `null` without commits, and for
 * a new branch that starts with a root commit.
 */
data class GitOutgoing(
    val commits: List<GitCommit>,
    val more: Boolean,
    val remoteCommit: String?,
    val behind: Int,
    val base: String?,
)

/** Why a remote refused a push (read from `git push --porcelain`). */
enum class GitPushRejection {
    /** The remote has commits the branch has not (`non-fast-forward`, `fetch first`): fetch and integrate, or force. */
    NON_FAST_FORWARD,

    /** The lease of a force push failed: the remote branch is not where the user saw it (`stale info`). */
    STALE,

    /** Any other reason, such as a hook of the remote declining. */
    OTHER,
}

/**
 * What a push gave: git's [result] (for a failure its standard error, with a line in front that says what to do for a
 * [rejection]; for a success git's standard error as well, often empty, never the porcelain report), whether it was
 * [cancelled], and why the remote [rejected][rejection] it (`null` for a success and for a failure that is none, e.g. a
 * failing `pre-push` hook or a remote that cannot be reached). From the report of git for the pushed ref: [upToDate]
 * if the remote had it already (nothing was sent), [created] if it made a new branch on the remote.
 */
data class GitPushResult(
    val result: GitResult,
    val cancelled: Boolean = false,
    val rejection: GitPushRejection? = null,
    val upToDate: Boolean = false,
    val created: Boolean = false,
)

/**
 * A ref to delete on a remote (ADR 0078): its full name there ([ref]: `refs/heads/fix`, `refs/tags/v1`) and, for a
 * branch, the commit the remote must have it at ([expected], a full hash: the lease); `null` for a tag.
 */
data class GitRemoteDeletion(
    val ref: String,
    val expected: String? = null,
)

/** What became of a ref that a deletion on a remote pushed (ADR 0078). */
enum class GitRemoteDeletionOutcome {
    /** The remote deleted it; also a tag it did not have, as git reports it. */
    DELETED,

    /** The lease failed: the remote has the branch at another commit than expected. */
    STALE,

    /** The lease failed: the remote no longer has the branch. */
    MISSING,

    /** The lease failed, and whether the remote still has the branch could not be read. */
    STALE_OR_MISSING,

    /** The remote refused it for another reason, e.g. a protected or its default branch. */
    REJECTED,
}

/** What became of one ref of a deletion: its [outcome] and git's [summary] for a refusal (`[remote rejected] (…)`). */
data class GitRemoteRefResult(
    val outcome: GitRemoteDeletionOutcome,
    val summary: String = "",
)

/**
 * What a deletion on a remote gave (ADR 0078): git's [result] (a success only if every ref was deleted; a failure has
 * git's standard error), whether a cancel stopped the push ([cancelled]; one that stops the `ls-remote` after it only
 * leaves refs [STALE_OR_MISSING][GitRemoteDeletionOutcome.STALE_OR_MISSING]), and by full refname what became of each
 * ref git reported on ([outcomes]); a ref git did not report on (it failed before, e.g. the remote could not be reached)
 * is not in it.
 */
data class GitRemoteDeletionResult(
    val result: GitResult,
    val cancelled: Boolean = false,
    val outcomes: Map<String, GitRemoteRefResult> = emptyMap(),
)
