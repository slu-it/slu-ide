package dev.sluide.git

/** Why a conflicted file cannot be checked for conflict markers before continuing (ADR 0042). */
enum class GitConflictReason {
    /** Binary (by git's heuristic or attributes): git kept one side's version, without markers. */
    BINARY,

    /** Too large to read for the check. */
    TOO_LARGE,

    /** Deleted on one side, and the file is on disk: it will be committed. */
    DELETED_ON_ONE_SIDE,

    /** Deleted on disk: the deletion will be committed. */
    DELETED_ON_DISK,

    /** A submodule (gitlink). */
    SUBMODULE,

    /** A symbolic link. */
    SYMLINK,
}

/** A conflicted [path] that cannot be checked for conflict markers, and why. */
data class GitUncheckedConflict(
    val path: String,
    val reason: GitConflictReason,
)
