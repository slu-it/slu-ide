package dev.sluide.git

import java.time.Instant

/**
 * A repository on a git host: [url] is what git fetches from (https or ssh form), [host]/[owner]/[name] name its cache
 * folder ([RemoteSourceCache]).
 *
 * The URL must be the repository's own, so that a cache folder never holds another repository's commits: its host is
 * [host] ignoring case (for https with the port, e.g. `ghes.example.com:8443`; for ssh without user and port) and its
 * path is `owner/name`, optionally with `.git` or a trailing `/`, ignoring case as GitHub does. No credentials in an
 * https URL, no query. E.g. `https://github.com/actions/checkout.git`, `git@github.com:actions/checkout.git`,
 * `ssh://git@github.com/actions/checkout`. Otherwise fetches and reads with the network fail ("repository URL does
 * not match", "unsupported repository URL").
 */
data class RemoteRepository(
    val host: String,
    val owner: String,
    val name: String,
    val url: String,
)

/** The commit a ref was fetched as, and when. */
data class CachedCommit(
    val commit: String,
    val fetchedAt: Instant,
)

/** What [RemoteSourceCache.fetch] did. */
sealed interface RemoteFetch {
    data class Fetched(
        val commit: CachedCommit,
    ) : RemoteFetch

    /** The fetch failed; [reason] is short and meant for the user, e.g. "repository not found or no access". */
    data class Failed(
        val reason: String,
    ) : RemoteFetch
}

/** What [RemoteSourceCache.read] found. */
sealed interface RemoteRead {
    data class Found(
        val text: String,
    ) : RemoteRead

    /** The commit has no such file. */
    data object Missing : RemoteRead

    /** Reading without the network, and the content is not in the cache. */
    data object NotCached : RemoteRead

    /** The read failed; [reason] is short and meant for the user. */
    data class Failed(
        val reason: String,
    ) : RemoteRead
}
