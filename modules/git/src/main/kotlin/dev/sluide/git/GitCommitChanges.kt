package dev.sluide.git

/** What the Commit tab offers: changes of tracked files against HEAD, untracked files, and what git is in the middle of. */
data class GitCommitChanges(
    /** Tracked changes, paths relative to the root with `/`. */
    val files: List<GitChangedFile>,
    /** Untracked, not ignored files, relative paths with `/`. */
    val untracked: List<String>,
    /** A merge, rebase, pick, sequence, unmerged paths alone ([GitPendingConflicts], ADR 0107)… or `null`. */
    val inProgress: GitInProgress?,
    /** HEAD exists and is reachable from the current branch's upstream: amending would need a force push. */
    val headPushed: Boolean,
)
