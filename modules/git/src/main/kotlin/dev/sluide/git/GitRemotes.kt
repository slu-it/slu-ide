package dev.sluide.git

import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Stops a git command that may be stopped halfway, a fetch (ADR 0043), from any thread: [cancel] has git and what it
 * started stopped as a timeout would (SIGTERM, killed after 2 s). Once cancelled, it stays cancelled; a command that
 * has not started yet does not start.
 */
class GitCancellation {
    /** Completed by [cancel]; the runner of git waits for it besides git's end. */
    internal val signal = CompletableFuture<Unit>()

    val cancelled: Boolean get() = signal.isDone

    fun cancel() {
        signal.complete(Unit)
    }
}

/**
 * How the refs of a remote and the tags changed with a fetch (ADR 0043): its remote branches new, moved and deleted
 * (pruned), and tags new, moved and deleted (only with the user's `fetch.pruneTags`).
 */
data class GitFetchChanges(
    val newBranches: Int = 0,
    val updatedBranches: Int = 0,
    val prunedBranches: Int = 0,
    val newTags: Int = 0,
    val updatedTags: Int = 0,
    val prunedTags: Int = 0,
) {
    val none: Boolean get() = this == GitFetchChanges()

    operator fun plus(other: GitFetchChanges) =
        GitFetchChanges(
            newBranches + other.newBranches,
            updatedBranches + other.updatedBranches,
            prunedBranches + other.prunedBranches,
            newTags + other.newTags,
            updatedTags + other.updatedTags,
            prunedTags + other.prunedTags,
        )
}

/**
 * What fetching a remote gave: git's [result] (a failure may have updated some refs all the same, e.g. when git refuses
 * to move an existing tag), whether it was [cancelled], and how the refs [changed][changes] (`null` if that could not be
 * read).
 */
data class GitFetchResult(
    val result: GitResult,
    val cancelled: Boolean = false,
    val changes: GitFetchChanges? = null,
)

/**
 * Fetches the remotes of a repository (ADR 0043) with the git command line, one remote at a time:
 * `git fetch --prune --recurse-submodules=no --end-of-options <remote>`. Tags are fetched as git does by default
 * (those pointing into the fetched history); local tags are never pruned by Thoreau. Git never asks for anything:
 * `GIT_TERMINAL_PROMPT=0`, and ssh runs in batch mode unless the user chose how git runs ssh ([fetchEnvironment]).
 * Runs git on the calling thread.
 */
@Component
class GitRemotes(
    private val git: GitCli,
) {
    /** The names of all remotes of the repository at [root] (`git remote`), in git's order; `null` if git fails. */
    fun names(root: Path): List<String>? = git.remoteNames(root)

    /**
     * The remotes of the repository at [root] that fetching all of them fetches, in git's order: all but those with
     * `remote.<name>.skipFetchAll`, as `git fetch --all`; `null` if git fails.
     */
    fun remotes(root: Path): List<String>? {
        val names = git.remoteNames(root) ?: return null
        if (names.isEmpty()) return names
        val skipped = git.remotesSkippedByFetchAll(root) ?: return null
        return names.filter { it !in skipped }
    }

    /**
     * Fetches [remote], pruning its remote branches deleted on the server, within 10 minutes; [cancellation] stops git
     * (what it updated by then stays). A name that cannot be passed to git ([refusal]) fails without running git. The
     * [GitFetchResult.changes] compare the refs before and after, also after a failure or cancel.
     */
    fun fetch(
        root: Path,
        remote: String,
        cancellation: GitCancellation,
    ): GitFetchResult {
        refusal(remote)?.let { return GitFetchResult(GitResult(false, it)) }
        if (cancellation.cancelled) return GitFetchResult(GitResult(false, GitCli.CANCELLED), cancelled = true)
        val destinations = git.configValues(root, "remote.$remote.fetch")?.let(::refspecDestinations)
        val before = destinations?.let { snapshot(root, remote, it) }
        val environment = fetchEnvironment(git.environment, git.sshConfigured(root) == true)
        val arguments = listOf("fetch", "--prune", "--recurse-submodules=no", "--end-of-options", remote)
        val (result, exitValue) =
            git.writeWithExit(
                root,
                arguments,
                timeout = GitCli.FETCH_TIMEOUT,
                environment = environment,
                cancellation = cancellation,
            )
        val cancelled = exitValue == null && cancellation.cancelled
        val after = if (before == null || destinations == null) null else snapshot(root, remote, destinations)
        val changes = if (before != null && after != null) fetchChanges(before, after) else null
        return GitFetchResult(if (cancelled) GitResult(false, GitCli.CANCELLED) else result, cancelled, changes)
    }

    /**
     * The refs the fetch of [remote] writes, by full refname: those its refspecs' [destinations] match (where git puts
     * and prunes them; `refs/remotes/<remote>/` without refspecs), and the tags.
     */
    private fun snapshot(
        root: Path,
        remote: String,
        destinations: List<RefspecDestination>,
    ): Map<String, String>? {
        val matched = destinations.ifEmpty { listOf(RefspecDestination("refs/remotes/$remote/", glob = true)) }
        // for-each-ref matches whole path components: ask for the folder, keep what a destination matches.
        val folders = matched.map { it.prefix.substringBeforeLast('/') + "/" } + TAGS
        return git.refTips(root, folders.distinct())?.filterKeys { name ->
            name.startsWith(TAGS) || matched.any { it.matches(name) }
        }
    }
}

/**
 * Where a fetch refspec writes: below [prefix] for a [glob] (the part before its `*`, e.g. `refs/remotes/origin/`;
 * the `*` matches any rest, also with slashes), else the ref [prefix] itself.
 */
internal data class RefspecDestination(
    val prefix: String,
    val glob: Boolean,
) {
    fun matches(name: String) = if (glob) name.startsWith(prefix) else name == prefix
}

/**
 * Where the fetch [refspecs] (`remote.<name>.fetch`) write: their right-hand sides, without the `+`; negative refspecs
 * (`^…`) and those without a destination (only `FETCH_HEAD`) write nothing.
 */
internal fun refspecDestinations(refspecs: List<String>): List<RefspecDestination> =
    refspecs
        .map { it.trim().removePrefix("+") }
        .filter { !it.startsWith('^') && ':' in it }
        .map { it.substringAfter(':') }
        .filter { it.startsWith("refs/") }
        .map { destination ->
            if ('*' in destination) {
                RefspecDestination(destination.substringBefore('*'), glob = true)
            } else {
                RefspecDestination(destination, glob = false)
            }
        }

/**
 * How the refs went from [before] to [after] (full refnames to objects): tags, and all others (remote branches, or
 * wherever the refspecs write) as branches.
 */
internal fun fetchChanges(
    before: Map<String, String>,
    after: Map<String, String>,
): GitFetchChanges {
    fun count(
        tags: Boolean,
        test: (String) -> Boolean,
    ) = (before.keys + after.keys).count { it.startsWith(TAGS) == tags && test(it) }

    fun new(tags: Boolean) = count(tags) { it !in before }

    fun updated(tags: Boolean) = count(tags) { it in before && it in after && before[it] != after[it] }

    fun deleted(tags: Boolean) = count(tags) { it !in after }

    return GitFetchChanges(new(false), updated(false), deleted(false), new(true), updated(true), deleted(true))
}

private const val TAGS = "refs/tags/"

/**
 * What a fetch adds to git's [environment] (ADR 0043): `GIT_SSH_COMMAND=ssh -o BatchMode=yes`, so that ssh never asks
 * for a passphrase, password or host key on the terminal Thoreau may have been started from (ssh reads `/dev/tty`, not
 * its standard input); nothing if the user chose how git runs ssh: `GIT_SSH_COMMAND`, `GIT_SSH` or `GIT_SSH_VARIANT` in
 * the environment, or `core.sshCommand` or `ssh.variant` configured ([sshConfigured]), which the variable would
 * override or not fit.
 */
internal fun fetchEnvironment(
    environment: Map<String, String>,
    sshConfigured: Boolean,
): Map<String, String> {
    val chosen = sshConfigured || listOf("GIT_SSH_COMMAND", "GIT_SSH", "GIT_SSH_VARIANT").any { it in environment }
    return if (chosen) emptyMap() else mapOf("GIT_SSH_COMMAND" to "ssh -o BatchMode=yes")
}
