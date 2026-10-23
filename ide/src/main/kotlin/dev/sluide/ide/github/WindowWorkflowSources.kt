package dev.sluide.ide.github

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.git.CachedCommit
import dev.sluide.git.GitCancellation
import dev.sluide.git.RemoteFetch
import dev.sluide.git.RemoteRead
import dev.sluide.git.RemoteRepository
import dev.sluide.git.RemoteSourceCache
import dev.sluide.githubactions.RemoteFile
import dev.sluide.githubactions.RemoteReader
import dev.sluide.githubactions.RemoteSources
import dev.sluide.githubactions.RemoteText
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** How often a running fetch or read checks whether its reader was cancelled. */
private val CANCEL_POLL = TimeUnit.MILLISECONDS.toNanos(200)

/** A full SHA-1 or SHA-256 commit hash, as the cache tells them (its own check is internal to `git`). */
private val FULL_COMMIT = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")

/** The cache's reasons for a host out of reach (`RemoteSourceCache`'s failure reasons). */
private val UNREACHABLE = setOf("network error", "timed out")

/** What the window's remote sources use of the [RemoteSourceCache]; tests give fakes. */
internal interface RemoteCache {
    fun commit(
        repository: RemoteRepository,
        ref: String,
    ): CachedCommit?

    fun fetch(
        repository: RemoteRepository,
        ref: String,
        cancellation: GitCancellation,
    ): RemoteFetch

    fun read(
        repository: RemoteRepository,
        commit: String,
        path: String,
        network: Boolean,
        cancellation: GitCancellation,
    ): RemoteRead

    companion object {
        fun of(cache: RemoteSourceCache): RemoteCache =
            object : RemoteCache {
                override fun commit(
                    repository: RemoteRepository,
                    ref: String,
                ) = cache.commit(repository, ref)

                override fun fetch(
                    repository: RemoteRepository,
                    ref: String,
                    cancellation: GitCancellation,
                ) = cache.fetch(repository, ref, cancellation)

                override fun read(
                    repository: RemoteRepository,
                    commit: String,
                    path: String,
                    network: Boolean,
                    cancellation: GitCancellation,
                ) = cache.read(repository, commit, path, network, cancellation)
            }
    }
}

/**
 * The remote files of one window's workflow graphs (ADR 0076), from the remote source [cache]: a repository is looked
 * for on the project's origin host, if that is not github.com, then on github.com ([remoteRepositories]). The origin's
 * URL ([originUrl] of the window's [folder], which runs git) is read only if the folder's trust allows git ([trusted],
 * which may wait for the trust checks until the reader is cancelled), at most once per reader, when it first reads;
 * otherwise only github.com is asked, by https. A read cancelled while it waited reads nothing, and the wait decides
 * nothing: a later read asks again if the reader is no longer cancelled (one with the network stays cancelled).
 *
 * A reader without the network reads what the cache has: the first host that has the ref. One with the network fetches
 * each host, repository and ref at most once (a full commit SHA only if not cached), trying github.com when the
 * origin's host fails, and lets git fetch file contents as they are read; a host it could not reach (network error,
 * timeout) it does not ask again. A running git stops soon after the reader is cancelled. Readers are used from one
 * thread at a time, never the FX thread; several may run at once.
 */
internal class WindowWorkflowSources(
    private val cache: RemoteCache,
    private val folder: () -> Path?,
    private val trusted: (cancelled: () -> Boolean) -> Boolean,
    private val originUrl: (Path) -> String?,
) : RemoteSources {
    override fun reader(
        network: Boolean,
        cancelled: () -> Boolean,
    ): RemoteReader = if (network) FetchingReader(cancelled) else CachedReader(cancelled)

    private abstract inner class Reader : RemoteReader {
        /** Whether the reader was cancelled; also what its wait for the trust stops on. */
        abstract fun stopped(): Boolean

        /** Whether [origin] was decided: by a wait for the trust that was not cancelled. */
        private var originDecided = false
        private var origin: OriginHost? = null

        /**
         * Where to look for [file]; `null` once the reader is cancelled. A wait for the trust that was cancelled
         * decides nothing: a later read asks again.
         */
        fun repositories(file: RemoteFile): List<RemoteRepository>? {
            if (!originDecided) {
                val folder = folder()
                var waitCancelled = false
                val allowed = folder != null && trusted { stopped().also { if (it) waitCancelled = true } }
                if (waitCancelled || stopped()) return null
                origin = folder?.takeIf { allowed }?.let(originUrl)?.let(::originHost)
                originDecided = true
            }
            return remoteRepositories(origin, file.owner, file.repo).takeUnless { stopped() }
        }
    }

    private inner class CachedReader(
        private val cancelled: () -> Boolean,
    ) : Reader() {
        override fun stopped() = cancelled()

        override fun read(file: RemoteFile): RemoteText {
            if (cancelled()) return RemoteText.NotFetched
            val repositories = repositories(file) ?: return RemoteText.NotFetched
            for (repository in repositories) {
                val cached = cache.commit(repository, file.ref) ?: continue
                if (cancelled()) return RemoteText.NotFetched
                val read = cache.read(repository, cached.commit, file.path, network = false, GitCancellation())
                return text(read, cached, file.ref)
            }
            return RemoteText.NotFetched
        }
    }

    private inner class FetchingReader(
        private val cancelled: () -> Boolean,
    ) : Reader() {
        private val cancellation = GitCancellation()

        /** Used from one thread at a time, as the reader. */
        private val fetches = HashMap<Pair<RemoteRepository, String>, RemoteFetch>()

        /**
         * Hosts that could not be reached, with the reason: not asked again by this reader, so that a server out of
         * reach (a GHES host off the VPN) costs one timeout, not one per reference.
         */
        private val unreachable = HashMap<String, String>()

        override fun read(file: RemoteFile): RemoteText {
            if (stopped()) return RemoteText.NotFetched
            val repositories = repositories(file) ?: return RemoteText.NotFetched
            val failures = mutableListOf<Pair<String, String>>()
            for (repository in repositories) {
                val fetched = fetched(repository, file.ref)
                if (stopped()) return RemoteText.NotFetched
                if (fetched is RemoteFetch.Failed) {
                    failures += repository.host to fetched.reason
                    continue
                }
                val commit = (fetched as RemoteFetch.Fetched).commit
                val network = repository.host !in unreachable
                val read = watched { cache.read(repository, commit.commit, file.path, network, cancellation) }
                if (read is RemoteRead.Failed) remember(repository, read.reason)
                return if (stopped()) RemoteText.NotFetched else text(read, commit, file.ref)
            }
            return RemoteText.Failed(reason(failures))
        }

        private fun remember(
            repository: RemoteRepository,
            reason: String,
        ) {
            if (reason in UNREACHABLE) unreachable.putIfAbsent(repository.host, reason)
        }

        /** Whether the reader was cancelled, for good once it was; cancels the git it runs then. */
        override fun stopped(): Boolean {
            if (!cancellation.cancelled && cancelled()) cancellation.cancel()
            return cancellation.cancelled
        }

        private fun fetched(
            repository: RemoteRepository,
            ref: String,
        ): RemoteFetch =
            fetches.getOrPut(repository to ref) {
                val cached = if (FULL_COMMIT.matches(ref)) cache.commit(repository, ref) else null
                cached?.let(RemoteFetch::Fetched)
                    ?: unreachable[repository.host]?.let(RemoteFetch::Failed)
                    ?: watched { cache.fetch(repository, ref, cancellation) }.also {
                        if (it is RemoteFetch.Failed) remember(repository, it.reason)
                    }
            }

        /** Runs [action] while a virtual thread checks every 200 ms whether the reader was cancelled. */
        private fun <T> watched(action: () -> T): T {
            val done = AtomicBoolean(false)
            val watcher =
                NamedThreads.virtual("Workflow references cancel watch") {
                    while (!done.get() && !stopped()) LockSupport.parkNanos(CANCEL_POLL)
                }
            try {
                return action()
            } finally {
                done.set(true)
                LockSupport.unpark(watcher)
            }
        }
    }
}

/** The reason of all hosts that failed: one if they agree, else each with its host. */
private fun reason(failures: List<Pair<String, String>>): String {
    val reasons = failures.map { it.second }.distinct()
    return reasons.singleOrNull() ?: failures.joinToString("; ") { (host, reason) -> "$host: $reason" }
}

private fun text(
    read: RemoteRead,
    commit: CachedCommit,
    ref: String,
): RemoteText =
    when (read) {
        is RemoteRead.Found -> {
            RemoteText.Found(
                read.text,
                commit.commit,
                commit.fetchedAt.takeUnless {
                    FULL_COMMIT.matches(ref)
                },
            )
        }

        RemoteRead.Missing -> {
            RemoteText.Missing
        }

        RemoteRead.NotCached -> {
            RemoteText.NotFetched
        }

        is RemoteRead.Failed -> {
            RemoteText.Failed(read.reason)
        }
    }
