package dev.sluide.git

/**
 * What renaming a remote gave: git's [result], whether its fetch refspecs were rewritten to the new name
 * ([refspecsUpdated]; `false` if one is negative or writes outside `refs/remotes/<old>/`, which git leaves as it is),
 * and whether git failed [partial]ly: the configuration was renamed but the remote branches were not moved (a lock on
 * `packed-refs`, say).
 */
data class GitRemoteRename(
    val result: GitResult,
    val refspecsUpdated: Boolean,
    val partial: Boolean = false,
)

/**
 * The URLs of a remote for an edit: the first `url` and the first `pushurl` (`null` if not set), and whether there are
 * several of either; git cannot change one of several without naming it, so callers leave such a field alone.
 * They may hold credentials: show them only through [maskedUrl].
 */
data class GitRemoteUrls(
    val url: String?,
    val pushUrl: String?,
    val severalUrls: Boolean,
    val severalPushUrls: Boolean,
) {
    /** The URLs masked ([maskedUrl]), so that logging or showing this object never leaks credentials. */
    override fun toString() =
        "GitRemoteUrls(url=${url?.let(::maskedUrl)}, pushUrl=${pushUrl?.let(::maskedUrl)}, " +
            "severalUrls=$severalUrls, severalPushUrls=$severalPushUrls)"
}

/**
 * What removing a remote deletes and unsets: [remoteBranches] refs below `refs/remotes/` as `git remote remove`
 * deletes them, without the symbolic `refs/remotes/<name>/HEAD`, the local [trackingBranches] whose
 * `branch.<name>.remote` or `.pushRemote` it is (sorted), and whether it is [pushDefault] (`remote.pushDefault`).
 */
data class GitRemoteRemoval(
    val remoteBranches: Int,
    val trackingBranches: List<String>,
    val pushDefault: Boolean,
)
