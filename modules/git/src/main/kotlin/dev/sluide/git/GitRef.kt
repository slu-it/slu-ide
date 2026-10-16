package dev.sluide.git

/** What a [GitRef] is, in the order the git log shows them (ADR 0037). */
enum class GitRefKind {
    /** `HEAD` itself, attached to a branch or detached. */
    HEAD,
    LOCAL_BRANCH,
    REMOTE_BRANCH,
    TAG,
}

/**
 * A branch, remote branch, tag or `HEAD` and the commit it points at (ADR 0037). [name] is the full refname
 * (`refs/remotes/origin/main`), or `HEAD`; [commit] the full object name of the commit, for an annotated tag the one
 * the tag points at.
 */
data class GitRef(
    val name: String,
    val kind: GitRefKind,
    val commit: String,
) {
    /** The name as the user knows it: `main`, `origin/main`, `v1.0`, `HEAD`. */
    val shortName: String
        get() =
            when (kind) {
                GitRefKind.HEAD -> name
                GitRefKind.LOCAL_BRANCH -> name.removePrefix("refs/heads/")
                GitRefKind.REMOTE_BRANCH -> name.removePrefix("refs/remotes/")
                GitRefKind.TAG -> name.removePrefix("refs/tags/")
            }
}

/**
 * The refs of a repository at one moment (ADR 0037): its branches, remote branches, tags and `HEAD`, without symbolic
 * refs such as `refs/remotes/origin/HEAD`. [currentBranch] is the full refname of the checked-out branch
 * (`refs/heads/main`), `null` when `HEAD` is detached.
 */
data class GitRefs(
    val refs: List<GitRef>,
    val currentBranch: String?,
) {
    // Built right away, on the thread that reads the refs, not later on the FX thread (thousands of tags).
    private val byCommit: Map<String, List<GitRef>> =
        refs.groupBy { it.commit }.mapValues { (_, refs) -> refs.sortedWith(ORDER) }

    /** The refs pointing at [commit]: `HEAD`, then local branches, remote branches and tags, each by name. */
    fun at(commit: String): List<GitRef> = byCommit[commit].orEmpty()

    private companion object {
        val ORDER = compareBy<GitRef>({ it.kind }, { it.name })
    }
}
