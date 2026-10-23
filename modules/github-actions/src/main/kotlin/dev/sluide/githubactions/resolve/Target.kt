package dev.sluide.githubactions.resolve

/** A repository at a ref on GitHub, as a remote `uses:` names it. */
internal data class RemoteRepository(
    val owner: String,
    val repo: String,
    val ref: String,
)

/**
 * A file or folder a `uses:` leads to: [path] (normalised, relative to the repository's root) in the workflow's own
 * repository if [repository] is null, else in that remote one. What is being expanded, and so what cycles are found by.
 */
internal data class Target(
    val repository: RemoteRepository?,
    val path: String,
) {
    /** As a `uses:` would name it: `./path`, `owner/repo/path@ref` (`owner/repo@ref` for the root). */
    val written: String
        get() =
            if (repository == null) {
                "./$path"
            } else {
                val folder = if (path.isEmpty()) "" else "/$path"
                "${repository.owner}/${repository.repo}$folder@${repository.ref}"
            }
}
