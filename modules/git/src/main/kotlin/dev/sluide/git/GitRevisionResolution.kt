package dev.sluide.git

/** What a revision (a hash, branch, tag or anything git understands, such as `HEAD~3`) names (ADR 0045). */
sealed interface GitRevisionResolution {
    /** A commit, by its full object name; annotated tags are peeled to the commit they tag. */
    data class Commit(
        val hash: String,
    ) : GitRevisionResolution

    /** No commit: an unknown revision, or an object that is not a commit and does not lead to one. */
    data object Missing : GitRevisionResolution

    /** A short hash that several objects start with. */
    data object Ambiguous : GitRevisionResolution
}
