package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.fetchEnvironment
import dev.sluide.git.remote.CANCELLED
import dev.sluide.git.remote.RemoteRefStore
import dev.sluide.git.remote.RepositoryLocks
import dev.sluide.git.remote.TIMED_OUT
import dev.sluide.git.remote.commitRefusal
import dev.sluide.git.remote.isFullCommit
import dev.sluide.git.remote.locationRefusal
import dev.sluide.git.remote.pathRefusal
import dev.sluide.git.remote.refRefusal
import dev.sluide.git.remote.remoteFailure
import dev.sluide.git.remote.repositoryRefusal
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = System.getLogger(RemoteSourceCache::class.java.name)

/**
 * Bare repositories under `~/.sluide/cache/git/<host>/<owner>/<name>.git`, one per remote repository, to read single
 * files of it at a ref, e.g. what a GitHub Actions workflow `uses:` (ADR 0076). A ref is fetched shallow and without
 * file contents (`--depth=1 --filter=blob:none`, a partial clone); a file's content is fetched when it is read with the
 * network allowed, and stays.
 *
 * Git runs only in these repositories, never in a project, each command but `init --bare` with `--git-dir`: hooks are
 * off (`core.hooksPath=/dev/null`, created without templates), only https and ssh are allowed (`GIT_ALLOW_PROTOCOL`,
 * which beats any `protocol.*` setting, and `protocol.*` as well), nothing asks for anything (ssh in batch mode, no
 * askpass program for git or ssh, no credential manager window) and git speaks English (`LC_ALL=C`) so that its errors
 * can be told apart. Batch mode replaces a configured `core.sshCommand`; but if the user's environment sets
 * `GIT_SSH_COMMAND`, `GIT_SSH` or `GIT_SSH_VARIANT`, their ssh runs as they chose, and it may ask for a passphrase on
 * the terminal Thoreau was started from, if any, until the network timeout. The URL must be the repository's own
 * ([RemoteRepository]) and is given to git per command (`-c remote.sluide-cache.url`), never stored.
 * A read without the network has no URL, no protocol allowed and `GIT_NO_LAZY_FETCH=1`: with git 2.44 or later git
 * does not even start a lazy fetch, before that the one it starts fails at once. Fetches of one repository take turns,
 * also with other running apps (a file lock); reads run beside them. A cache folder git itself does not take for a
 * repository (`rev-parse --git-dir`, after a failed fetch) is created anew; one a failed fetch created is deleted
 * again. A download is bounded by the timeout only.
 *
 * Blocking, git runs on the calling thread: never call it on the JavaFX thread. Safe to call from several threads.
 */
@Component
class RemoteSourceCache internal constructor(
    private val git: GitCli,
    folder: Path,
    /** Tests only: allows `file://` URLs, local "servers". */
    private val allowFileUrls: Boolean,
) {
    constructor(git: GitCli, folder: Path) : this(git, folder, allowFileUrls = false)

    @Autowired
    constructor(git: GitCli) : this(git, Path.of(System.getProperty("user.home"), ".sluide", "cache", "git"))

    private val folder: Path = folder.toAbsolutePath().normalize()

    /** The commit [ref] was last fetched as, from the cache only; `null` if it never was (or the ref is invalid). */
    fun commit(
        repository: RemoteRepository,
        ref: String,
    ): CachedCommit? {
        if (locationRefusal(repository) != null || refRefusal(ref) != null) return null
        return RemoteRefStore.read(directory(repository))[normalized(ref)]
    }

    /**
     * Fetches [ref] (branch, tag or full commit hash) from the network into the cache and remembers its commit; a full
     * commit hash fetched before is not fetched again. Within 2 minutes; [cancellation] stops git, and nothing is
     * remembered then. A server that does not support the filter sends the files too (git warns and goes on).
     */
    fun fetch(
        repository: RemoteRepository,
        ref: String,
        cancellation: GitCancellation = GitCancellation(),
    ): RemoteFetch {
        (repositoryRefusal(repository, allowFileUrls) ?: refRefusal(ref))?.let { return RemoteFetch.Failed(it) }
        val wanted = normalized(ref)
        if (isFullCommit(wanted)) commit(repository, wanted)?.let { return RemoteFetch.Fetched(it) }
        return fetchLocked(repository, wanted, cancellation)
    }

    /**
     * The text of [path] (relative, `/`-separated, never `..`) at [commit], at most 1 MB, decoded as UTF-8. With
     * [network] false nothing touches the network: a commit or content not in the cache is [RemoteRead.NotCached]. With
     * [network] true a commit not in the cache is fetched first, and git fetches the content if it is missing.
     */
    fun read(
        repository: RemoteRepository,
        commit: String,
        path: String,
        network: Boolean,
        cancellation: GitCancellation = GitCancellation(),
    ): RemoteRead {
        val location = if (network) repositoryRefusal(repository, allowFileUrls) else locationRefusal(repository)
        (location ?: commitRefusal(commit) ?: pathRefusal(path))?.let { return RemoteRead.Failed(it) }
        if (!git.available) return RemoteRead.Failed(NOT_AVAILABLE)
        if (cancellation.cancelled) return RemoteRead.Failed(CANCELLED)
        val wanted = commit.lowercase()
        val directory = directory(repository)
        val present = if (Files.isDirectory(directory)) hasCommit(directory, wanted, cancellation) else null
        if (present != null) failure(present.execution, cancellation)?.let { return RemoteRead.Failed(it) }
        if (present?.answer != true) {
            if (!network) return RemoteRead.NotCached
            val fetched = fetchLocked(repository, wanted, cancellation)
            if (fetched is RemoteFetch.Failed) return RemoteRead.Failed(fetched.reason)
        }
        val url = repository.url.takeIf { network }
        val blob = blob(directory, wanted, path, url, cancellation)
        failure(blob.execution, cancellation)?.let { return RemoteRead.Failed(it) }
        val id =
            blob.answer
                ?: return if (blob.execution?.exitValue == 0) RemoteRead.Missing else RemoteRead.Failed(READ_FAILED)
        return content(directory, id, url, cancellation)
    }

    private fun fetchLocked(
        repository: RemoteRepository,
        ref: String,
        cancellation: GitCancellation,
    ): RemoteFetch {
        if (!git.available) return RemoteFetch.Failed(NOT_AVAILABLE)
        if (cancellation.cancelled) return RemoteFetch.Failed(CANCELLED)
        val directory = directory(repository)
        val start = System.nanoTime()
        val result =
            try {
                RepositoryLocks.withLock(
                    directory,
                    cancellation,
                ) { fetchInto(repository, directory, ref, cancellation) }
                    ?: RemoteFetch.Failed(CANCELLED)
            } catch (e: IOException) {
                log.log(System.Logger.Level.WARNING, "Cannot write the cache $directory", e)
                RemoteFetch.Failed(NOT_WRITABLE)
            } catch (e: UncheckedIOException) {
                log.log(System.Logger.Level.WARNING, "Cannot write the cache $directory", e)
                RemoteFetch.Failed(NOT_WRITABLE)
            }
        val millis = (System.nanoTime() - start) / 1_000_000
        val what = "$ref of ${repository.host}/${repository.owner}/${repository.name}"
        if (result is RemoteFetch.Fetched) {
            log.log(System.Logger.Level.INFO, "Fetched $what as ${result.commit.commit} in $millis ms")
        } else if (result is RemoteFetch.Failed) {
            log.log(System.Logger.Level.WARNING, "Fetching $what failed in $millis ms: ${result.reason}")
        }
        return result
    }

    /**
     * Fetches [ref] into the repository at [directory], created if missing or broken; under its lock. A repository
     * created by this call is deleted again unless the fetch stored something (also when cancelled), so that a
     * repository that does not exist leaves no folder; the lock file stays.
     */
    private fun fetchInto(
        repository: RemoteRepository,
        directory: Path,
        ref: String,
        cancellation: GitCancellation,
    ): RemoteFetch {
        var created = false
        var result: RemoteFetch? = null
        try {
            result = fetchInto(repository, directory, ref, cancellation) { created = true }
            return result
        } finally {
            if (created && result !is RemoteFetch.Fetched) discard(directory)
        }
    }

    private fun fetchInto(
        repository: RemoteRepository,
        directory: Path,
        ref: String,
        cancellation: GitCancellation,
        creating: () -> Unit,
    ): RemoteFetch {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.delete(directory)
            creating()
            create(directory)?.let { return RemoteFetch.Failed(it) }
        }
        var execution = gitFetch(repository, directory, ref, cancellation)
        if (execution?.exitValue == 128 && !isRepository(directory, cancellation)) {
            log.log(System.Logger.Level.WARNING, "The cache $directory is no git repository; creating it anew")
            creating()
            recreate(directory)?.let { return RemoteFetch.Failed(it) }
            execution = gitFetch(repository, directory, ref, cancellation)
        }
        failure(execution, cancellation)?.let { return RemoteFetch.Failed(it) }
        val fetched = execution ?: return RemoteFetch.Failed("git could not be run")
        if (fetched.exitValue != 0) {
            return RemoteFetch.Failed(remoteFailure(fetched.errors.toString(Charsets.UTF_8), "fetch failed"))
        }
        val resolved =
            run(directory, listOf("rev-parse", "--verify", "--quiet", "FETCH_HEAD^{commit}"), null, cancellation)
        failure(resolved, cancellation)?.let { return RemoteFetch.Failed(it) }
        val output = resolved?.takeIf { it.exitValue == 0 && it.complete }?.output
        val commit =
            output?.toString(Charsets.UTF_8)?.trim()?.takeIf(::isFullCommit)
                ?: return RemoteFetch.Failed("'$ref' is no commit")
        // Keeps the commit from being pruned, should git ever collect garbage here (gc.auto is off).
        val kept =
            run(
                directory,
                listOf("update-ref", "refs/sluide/cache/${refId(ref)}", commit),
                null,
                cancellation,
                write = true,
            )
        failure(kept, cancellation)?.let { return RemoteFetch.Failed(it) }
        if (kept?.exitValue != 0) return RemoteFetch.Failed("could not update the cache")
        if (cancellation.cancelled) return RemoteFetch.Failed(CANCELLED)
        val cached = CachedCommit(commit, Instant.now().truncatedTo(ChronoUnit.MILLIS))
        RemoteRefStore.put(directory, ref, cached)
        return RemoteFetch.Fetched(cached)
    }

    /**
     * Whether git takes [directory] for a repository, asked without the network (`rev-parse --git-dir`): not after a
     * failed fetch whatever the server said, so that only a broken cache is created anew.
     */
    private fun isRepository(
        directory: Path,
        cancellation: GitCancellation,
    ): Boolean {
        val execution = run(directory, listOf("rev-parse", "--git-dir"), null, cancellation)
        val broken = execution != null && execution.exitValue == 128 && execution.complete
        return !broken
    }

    private fun gitFetch(
        repository: RemoteRepository,
        directory: Path,
        ref: String,
        cancellation: GitCancellation,
    ): GitCli.Execution? {
        val arguments =
            listOf(
                "fetch",
                "--depth=1",
                "--filter=blob:none",
                "--no-tags",
                "--recurse-submodules=no",
                "--end-of-options",
                REMOTE,
                ref,
            )
        return run(directory, arguments, repository.url, cancellation, write = true)
    }

    /** Whether [commit] is in the repository at [directory]; without the network. */
    private fun hasCommit(
        directory: Path,
        commit: String,
        cancellation: GitCancellation,
    ): Answer<Boolean> {
        val execution =
            run(directory, listOf("rev-parse", "--verify", "--quiet", "$commit^{commit}"), null, cancellation)
        return Answer(
            execution,
            when (execution?.exitValue) {
                0 -> true
                1 -> false
                else -> null
            },
        )
    }

    /** The object id of the file at [path] in [commit]; none if the commit has no such file (exit 0) or git failed. */
    private fun blob(
        directory: Path,
        commit: String,
        path: String,
        url: String?,
        cancellation: GitCancellation,
    ): Answer<String> {
        val arguments = listOf("--literal-pathspecs", "ls-tree", "-z", commit, "--", path)
        val execution = run(directory, arguments, url, cancellation)
        if (execution?.exitValue != 0 || !execution.complete) return Answer(execution, null)
        // <mode> SP <type> SP <object> TAB <path> NUL
        val id =
            execution.output
                .toString(Charsets.UTF_8)
                .split('\u0000')
                .firstOrNull { it.substringAfter('\t') == path && it.split(' ').getOrNull(1) == "blob" }
                ?.substringBefore('\t')
                ?.substringAfterLast(' ')
        return Answer(execution, id)
    }

    private fun content(
        directory: Path,
        id: String,
        url: String?,
        cancellation: GitCancellation,
    ): RemoteRead {
        val network = url != null
        val execution = run(directory, listOf("cat-file", "blob", id), url, cancellation, network, MAX_SIZE)
        failure(execution, cancellation)?.let { return RemoteRead.Failed(it) }
        if (execution == null) return RemoteRead.Failed(READ_FAILED)
        if (execution.overflowed) return RemoteRead.Failed("over 1 MB")
        val read = execution.exitValue == 0 && execution.complete
        if (read) return RemoteRead.Found(execution.output.toString(Charsets.UTF_8))
        if (network) return RemoteRead.Failed(remoteFailure(execution.errors.toString(Charsets.UTF_8), READ_FAILED))
        val exists = run(directory, listOf("cat-file", "-e", id), null, cancellation)
        failure(exists, cancellation)?.let { return RemoteRead.Failed(it) }
        // The commit's tree lists the file, so it is a file content not fetched yet; with git before 2.44 the lazy
        // fetch git tries fails (no URL, no protocol allowed) and -e fails too.
        return if (exists?.exitValue == 0) RemoteRead.Failed(READ_FAILED) else RemoteRead.NotCached
    }

    /** Creates the bare repository at [directory] in a sibling folder first, so that it appears complete or not at all. */
    private fun create(directory: Path): String? {
        Files.createDirectories(directory.parent)
        val fresh = directory.resolveSibling("${directory.fileName}.new")
        deleteRecursively(fresh)
        val arguments = listOf("init", "--bare", "--quiet", "--template=", fresh.toString())
        val init = git.execute(arguments, directory.parent, LOCAL_TIMEOUT, null, write = true, LOCAL_ENVIRONMENT)
        if (init?.exitValue != 0) return "could not create the cache"
        val config = if (allowFileUrls) CONFIG + "[protocol \"file\"]\n\tallow = always\n" else CONFIG
        Files.writeString(fresh.resolve("config"), config, StandardOpenOption.APPEND)
        Files.move(fresh, directory, StandardCopyOption.ATOMIC_MOVE)
        return null
    }

    /** Deletes the repository at [directory] that a failed fetch created; a failure to is only logged. */
    private fun discard(directory: Path) {
        try {
            deleteRecursively(directory)
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot delete $directory", e)
        } catch (e: UncheckedIOException) {
            log.log(System.Logger.Level.WARNING, "Cannot delete $directory", e)
        }
    }

    /** Moves the broken repository at [directory] aside, deletes it, and creates it anew. */
    private fun recreate(directory: Path): String? {
        val old = directory.resolveSibling("${directory.fileName}.old")
        deleteRecursively(old)
        Files.move(directory, old, StandardCopyOption.ATOMIC_MOVE)
        try {
            deleteRecursively(old)
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot delete $old", e)
        } catch (e: UncheckedIOException) {
            log.log(System.Logger.Level.WARNING, "Cannot delete $old", e)
        }
        return create(directory)
    }

    /**
     * Runs git with [arguments] in the cached repository at [directory]. With a [url], git may connect to it (https and
     * ssh, or `file` in tests) and fetches what is missing; without, it cannot connect at all.
     */
    private fun run(
        directory: Path,
        arguments: List<String>,
        url: String?,
        cancellation: GitCancellation,
        write: Boolean = false,
        maxOutput: Long? = null,
    ): GitCli.Execution? {
        val file = if (allowFileUrls) "always" else "never"
        val protocols =
            if (url == null) {
                OFFLINE_PROTOCOLS
            } else {
                ONLINE_PROTOCOLS + listOf("-c", "protocol.file.allow=$file", "-c", "remote.$REMOTE.url=$url")
            }
        // GIT_ALLOW_PROTOCOL, if set, is all git asks: it beats protocol.* (also the user's, e.g. file for a URL that
        // url.<base>.insteadOf rewrites) and one inherited from the environment.
        val environment =
            if (url == null) {
                LOCAL_ENVIRONMENT + mapOf("GIT_NO_LAZY_FETCH" to "1", "GIT_ALLOW_PROTOCOL" to "")
            } else {
                val allowed = if (allowFileUrls) "https:ssh:file" else "https:ssh"
                LOCAL_ENVIRONMENT + NETWORK_ENVIRONMENT + ("GIT_ALLOW_PROTOCOL" to allowed) +
                    fetchEnvironment(git.environment, sshConfigured = false)
            }
        val options = listOf("--git-dir=$directory") + protocols
        val timeout = if (url != null) NETWORK_TIMEOUT else LOCAL_TIMEOUT
        return git.execute(
            options + arguments,
            directory,
            timeout,
            null,
            write,
            environment,
            cancellation,
            maxOutput,
            guardFsmonitor = false,
            maxErrors = MAX_ERRORS,
        )
    }

    /**
     * Deletes [path] and what is in it, without following links; only ever below a host folder of the cache, which
     * [path] is built from validated names to be. Throws IOException, also for a path outside it.
     */
    private fun deleteRecursively(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(folder) || normalized.nameCount < folder.nameCount + 2) {
            throw IOException("Refusing to delete $path outside the cache $folder")
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(normalized).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    /** Why a run of git gave no answer: it could not run, was [cancelled][GitCancellation] or took too long. */
    private fun failure(
        execution: GitCli.Execution?,
        cancellation: GitCancellation,
    ): String? =
        when {
            cancellation.cancelled -> CANCELLED
            execution == null -> "git could not be run"
            execution.cancelled -> CANCELLED
            execution.exitValue == null -> TIMED_OUT
            else -> null
        }

    private fun directory(repository: RemoteRepository): Path =
        folder
            .resolve(repository.host.lowercase().replace(':', '_'))
            .resolve(repository.owner)
            .resolve("${repository.name}.git")

    /** The [answer] of git's [execution] (`null` if it gave none). */
    private class Answer<T>(
        val execution: GitCli.Execution?,
        val answer: T?,
    )

    private companion object {
        const val NOT_AVAILABLE = "git is not available"
        const val READ_FAILED = "could not read the file"
        const val NOT_WRITABLE = "cache folder not writable"

        /** Fetching a ref shallow and without file contents is small: the commit and its trees. */
        val NETWORK_TIMEOUT = 2.minutes
        val LOCAL_TIMEOUT = 10.seconds

        /**
         * The name of the remote everything is fetched from: one no user configuration sets, as `remote.origin.url` in a
         * global configuration would come before the URL given per command (git fetches from a remote's first URL).
         */
        const val REMOTE = "sluide-cache"

        /** The most of git's standard error kept of a write (a fetch: also what the server says). */
        const val MAX_ERRORS = 64L * 1024L

        /** The largest file read. */
        const val MAX_SIZE = 1024L * 1024L

        /** Appended to the configuration of a new cache repository; protocols are also given per command. */
        val CONFIG =
            listOf(
                "[core]",
                "hooksPath = /dev/null",
                "[gc]",
                "auto = 0",
                "[maintenance]",
                "auto = false",
                "[remote \"$REMOTE\"]",
                "promisor = true",
                "partialclonefilter = blob:none",
                "[protocol]",
                "allow = never",
                "[protocol \"https\"]",
                "allow = always",
                "[protocol \"ssh\"]",
                "allow = always",
            ).joinToString("\n", postfix = "\n")

        val ONLINE_PROTOCOLS =
            listOf("-c", "protocol.allow=never", "-c", "protocol.https.allow=always", "-c", "protocol.ssh.allow=always")

        val OFFLINE_PROTOCOLS =
            listOf(
                "-c",
                "protocol.allow=never",
                "-c",
                "protocol.https.allow=never",
                "-c",
                "protocol.ssh.allow=never",
                "-c",
                "protocol.file.allow=never",
            )

        /** Git's messages in English, to tell failures apart. */
        val LOCAL_ENVIRONMENT = mapOf("LC_ALL" to "C")

        /**
         * Nothing asks: no askpass program for git (an empty one counts as none) or ssh (`SSH_ASKPASS_REQUIRE`, also
         * for an ssh the user chose), no credential manager window.
         */
        val NETWORK_ENVIRONMENT =
            mapOf("GIT_ASKPASS" to "", "SSH_ASKPASS_REQUIRE" to "never", "GCM_INTERACTIVE" to "never")

        private fun normalized(ref: String) = if (isFullCommit(ref)) ref.lowercase() else ref

        /** A ref name for [ref] of one component, so that refs such as `a` and `a/b` never collide. */
        private fun refId(ref: String) =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ref.toByteArray(Charsets.UTF_8)))
    }
}
