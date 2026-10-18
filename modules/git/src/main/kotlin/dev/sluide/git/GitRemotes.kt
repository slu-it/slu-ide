package dev.sluide.git

import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Fetches the remotes of a repository (ADR 0043) with the git command line, one remote at a time:
 * `git fetch --prune --recurse-submodules=no --end-of-options <remote>`, or `--no-prune` for a remote whose fetch
 * refspecs write outside `refs/remotes/`. Tags are fetched as git does by default (those pointing into the fetched
 * history); local tags are pruned only with the user's `fetch.pruneTags` or `remote.<name>.pruneTags`. Git never asks
 * for anything: `GIT_TERMINAL_PROMPT=0`, and ssh runs in batch mode unless the user chose how git runs ssh
 * ([fetchEnvironment]).
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
     *
     * Pruning deletes every ref where a fetch refspec writes that the server does not have: for a mirror (every ref
     * fetched to itself) or any refspec into `refs/heads/` or `refs/tags/`, the local branches and tags the remote does
     * not have (checked with git 2.53). Such a remote, or one whose refspecs cannot be read, is fetched with
     * `--no-prune`, also against the user's `fetch.prune`; the result says it was not [pruned][GitFetchResult.pruned].
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
        val pruned = destinations != null && destinations.all { it.prefix.startsWith("refs/remotes/") }
        val prune = if (pruned) "--prune" else "--no-prune"
        val arguments = listOf("fetch", prune, "--recurse-submodules=no", "--end-of-options", remote)
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
        val outcome = if (cancelled) GitResult(false, GitCli.CANCELLED) else result
        return GitFetchResult(outcome, cancelled, changes, pruned)
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
