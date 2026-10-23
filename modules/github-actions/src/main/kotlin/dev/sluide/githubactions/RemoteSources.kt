package dev.sluide.githubactions

import java.time.Instant

/** A file in another repository, as a `uses:` names it: [path] relative to the repository root, normalised, never `..`. */
data class RemoteFile(
    val owner: String,
    val repo: String,
    val ref: String,
    val path: String,
)

/** What a [RemoteReader] found for a [RemoteFile]. */
sealed interface RemoteText {
    /** [commit] the ref was fetched as, [asOf] when (null for a full commit SHA ref, which never changes). */
    data class Found(
        val text: String,
        val commit: String,
        val asOf: Instant?,
    ) : RemoteText

    /** The repository at that ref has no such file. */
    data object Missing : RemoteText

    /** Not in the cache and the reader may not use the network. */
    data object NotFetched : RemoteText

    /** The repository or the file could not be read, for a short [reason]. */
    data class Failed(
        val reason: String,
    ) : RemoteText
}

/** Reads one drawing's remote files. */
fun interface RemoteReader {
    fun read(file: RemoteFile): RemoteText
}

/** Remote files of a window (ADR 0076), registered as a window service by `ide`. */
interface RemoteSources {
    /**
     * A reader for one drawing. With [network], each repository and ref is fetched (again, unless a full SHA) at most
     * once per reader, files lazily; without, only the cache is read. [cancelled] stops further reads. Blocking; never
     * on the FX thread.
     */
    fun reader(
        network: Boolean,
        cancelled: () -> Boolean,
    ): RemoteReader
}
