package dev.sluide.git

import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Where a push of a branch goes by default (ADR 0049): a [remote] and a [branch] on it, both short names, and whether
 * the pushed branch has an upstream ([hasUpstream]: `branch.<b>.merge` is set), so a push sets one if it has none.
 */
data class GitPushTarget(
    val remote: String,
    val branch: String,
    val hasUpstream: Boolean = false,
)

/**
 * What a push would send (ADR 0049): the [commits] of `HEAD` that the target does not have, newest first and at most
 * the asked limit ([more] says there are others); the commit the target's remote-tracking ref has ([remoteCommit],
 * `null` for a branch the remote does not have yet); how many commits that ref has that `HEAD` has not ([behind]: a
 * push without force would be rejected); and the commit to diff the whole push against ([base]): the merge base of
 * `HEAD` and the ref, for a new branch the first parent of the oldest outgoing commit; `null` without commits, and for
 * a new branch that starts with a root commit.
 */
data class GitOutgoing(
    val commits: List<GitCommit>,
    val more: Boolean,
    val remoteCommit: String?,
    val behind: Int,
    val base: String?,
)

/** Why a remote refused a push (read from `git push --porcelain`). */
enum class GitPushRejection {
    /** The remote has commits the branch has not (`non-fast-forward`, `fetch first`): fetch and integrate, or force. */
    NON_FAST_FORWARD,

    /** The lease of a force push failed: the remote branch is not where the user saw it (`stale info`). */
    STALE,

    /** Any other reason, such as a hook of the remote declining. */
    OTHER,
}

/**
 * What a push gave: git's [result] (for a failure its standard error, with a line in front that says what to do for a
 * [rejection]; for a success git's standard error as well, often empty, never the porcelain report), whether it was
 * [cancelled], and why the remote [rejected][rejection] it (`null` for a success and for a failure that is none, e.g. a
 * failing `pre-push` hook or a remote that cannot be reached). From the report of git for the pushed ref: [upToDate]
 * if the remote had it already (nothing was sent), [created] if it made a new branch on the remote.
 */
data class GitPushResult(
    val result: GitResult,
    val cancelled: Boolean = false,
    val rejection: GitPushRejection? = null,
    val upToDate: Boolean = false,
    val created: Boolean = false,
)

/**
 * Pushes a branch and tells what a push would send (ADR 0049) with the git command line. A push runs as a fetch does
 * (ADR 0043): git never asks for anything ([fetchEnvironment]), it stops after 10 minutes or on a cancel, and names
 * that cannot be passed to git ([refusal]) fail without running it. Runs git on the calling thread.
 */
@Component
class GitPush(
    private val git: GitCli,
) {
    /**
     * Where the local [branch] (short name) of the repository at [root] is pushed by default, as git decides it: the
     * remote of the first that is set of `branch.<b>.pushRemote`, `remote.pushDefault`, `branch.<b>.remote` (unless it
     * is `.`, a local upstream), the only remote, `origin`, the first remote. The branch is [branch] itself, as with
     * git's default `push.default=simple`, which refuses to push to an upstream of another name (a branch created from
     * `origin/main` tracks it, and must not end up in `main`); only with `push.default=upstream` (or `tracking`) it is
     * the upstream's (`branch.<b>.merge`) if the remote is the upstream's. `null` if there is no remote, or if git
     * fails.
     */
    fun pushTarget(
        root: Path,
        branch: String,
    ): GitPushTarget? {
        val names = git.remoteNames(root)?.takeIf { it.isNotEmpty() } ?: return null
        val pushRemote = lastSetting(root, "branch.$branch.pushRemote")
        val pushDefault = lastSetting(root, "remote.pushDefault")
        val configuredRemote = lastSetting(root, "branch.$branch.remote")
        val merge = lastSetting(root, "branch.$branch.merge")
        val mode = lastSetting(root, "push.default")
        if (pushRemote == null || pushDefault == null || configuredRemote == null || merge == null || mode == null) {
            return null
        }
        val upstreamRemote = configuredRemote.value?.takeIf { it != "." }
        val remote =
            pushRemote.value
                ?: pushDefault.value
                ?: upstreamRemote
                ?: names.singleOrNull()
                ?: names.firstOrNull { it == "origin" }
                ?: names.first()
        val upstreamBranch =
            merge.value
                ?.takeIf { it.startsWith(HEADS) }
                ?.removePrefix(HEADS)
                ?.ifEmpty { null }
        val toUpstream = mode.value in UPSTREAM_MODES && remote == upstreamRemote
        val target = if (toUpstream && upstreamBranch != null) upstreamBranch else branch
        return GitPushTarget(remote, target, hasUpstream = merge.value != null)
    }

    /** The last non-empty value of [key] (`.value`, `null` if unset) as git reads it; `null` itself if git fails. */
    private fun lastSetting(
        root: Path,
        key: String,
    ): Setting? = git.configValues(root, key)?.let { Setting(it.lastOrNull(String::isNotEmpty)) }

    private data class Setting(
        val value: String?,
    )

    /**
     * The full refname of the remote-tracking ref where git keeps `refs/heads/<branch>` of [remote]: the first of
     * `remote.<remote>.fetch` that maps it (the usual glob refspec or an exact one), else
     * `refs/remotes/<remote>/<branch>`. Whether the ref exists is another matter ([outgoing]).
     */
    fun trackingRef(
        root: Path,
        remote: String,
        branch: String,
    ): String {
        val source = HEADS + branch
        val refspecs = git.configValues(root, "remote.$remote.fetch").orEmpty()
        return refspecs.firstNotNullOfOrNull { mapped(it, source) } ?: "refs/remotes/$remote/$branch"
    }

    /**
     * What pushing `HEAD` to the [branch] of [remote] would send, at most [limit] commits: those of `HEAD` that the
     * [trackingRef] does not have, or for a branch that does not exist on the remote yet, those that no remote-tracking
     * ref of [remote] has (`git log HEAD --not --glob=<where its fetch refspecs write>*`, no limit on their number).
     * `null` if `HEAD` has no commits, a name cannot be passed to git, or git fails.
     */
    fun outgoing(
        root: Path,
        remote: String,
        branch: String,
        limit: Int = 1000,
    ): GitOutgoing? {
        require(limit > 0) { "limit must be positive: $limit" }
        if (refusal(remote) != null || refusal(branch) != null) return null
        if (git.headCommit(root) == null) return null
        val ref = trackingRef(root, remote, branch)
        val remoteCommit = git.refTips(root, listOf(ref.substringBeforeLast('/') + "/"))?.get(ref)
        val tracked = remoteCommit != null
        val destinations = destinations(root, remote)
        val globs = if (tracked) emptyList() else destinations.map(::glob)
        val filter =
            GitLogFilter(
                revisions = listOf("HEAD"),
                excluded = if (tracked) listOf(ref) else emptyList(),
                order = GitLogOrder.TOPOLOGICAL,
            )
        val listed = git.log(root, filter, 0, limit + 1, globs)?.let(GitLogParser::parse) ?: return null
        val commits = listed.take(limit)
        val more = listed.size > limit
        val behind = if (tracked) git.comparisonCounts(root, "HEAD", ref)?.second ?: return null else 0
        val base =
            when {
                commits.isEmpty() -> {
                    null
                }

                tracked -> {
                    git.mergeBase(root, "HEAD", ref)
                }

                else -> {
                    newBranchBase(
                        root,
                        destinations,
                        globs,
                        if (more) null else commits.last(),
                    ).getOrElse { return null }
                }
            }
        return GitOutgoing(commits, more, remoteCommit, behind, base)
    }

    /**
     * The first parent of the oldest outgoing commit of a new branch: of [last] if the list is complete, else read from
     * git. A [Result] failure if that could not be read; its value `null` for a root commit, and also when no ref of
     * [destinations] exists, then every commit is outgoing and the oldest is a root.
     */
    private fun newBranchBase(
        root: Path,
        destinations: List<RefspecDestination>,
        globs: List<String>,
        last: GitCommit?,
    ): Result<String?> {
        val unreadable = Result.failure<String?>(IllegalStateException("git could not be read"))
        val folders = destinations.map { it.prefix.substringBeforeLast('/') + "/" }.distinct()
        val tips = git.refTips(root, folders) ?: return unreadable
        if (tips.keys.none { name -> destinations.any { it.matches(name) } }) return Result.success(null)
        if (last != null) return Result.success(last.parents.firstOrNull())
        val oldest = git.lastOutgoing(root, globs) ?: return unreadable
        return Result.success(oldest.getOrNull(1))
    }

    /** Where the fetch refspecs of [remote] write; `refs/remotes/<remote>/` if it has none. */
    private fun destinations(
        root: Path,
        remote: String,
    ): List<RefspecDestination> =
        git
            .configValues(root, "remote.$remote.fetch")
            .orEmpty()
            .let(::refspecDestinations)
            .ifEmpty { listOf(RefspecDestination("refs/remotes/$remote/", glob = true)) }

    /**
     * Pushes the local [branch] to the [target] branch of [remote]
     * (`git push --porcelain --recurse-submodules=no [--set-upstream] [--force-with-lease=<ref>:<expected>]
     * --end-of-options <remote> refs/heads/<branch>:refs/heads/<target>`), within 10 minutes; [cancellation] stops git.
     * With [force] the push replaces the remote branch only if it is at [expected], a full hash (`null`: it must not
     * exist), never plain `--force`: the explicit value keeps a fetch between looking at the push and pushing from
     * weakening the lease. With [setUpstream] git makes the target the branch's upstream. The report of git
     * (`--porcelain`) tells for the pushed ref whether the remote rejected it ([GitPushResult.rejection], told in a line
     * in front of git's message), had it already or got it new. Names that cannot be passed to git ([refusal]) and an
     * [expected] that is no full hash fail without running git. One stopped after the [timeout] (10 minutes; shorter in
     * tests) may have reached the remote all the same, as a cancelled one may: its message says so.
     */
    fun push(
        root: Path,
        remote: String,
        branch: String,
        target: String,
        force: Boolean,
        expected: String?,
        setUpstream: Boolean,
        cancellation: GitCancellation,
        timeout: Duration = GitCli.FETCH_TIMEOUT,
    ): GitPushResult {
        val checked = listOfNotNull(remote, branch, target, expected.takeIf { force })
        checked.firstNotNullOfOrNull { refusal(it) }?.let { return GitPushResult(GitResult(false, it)) }
        if (force && expected != null && !FULL_HASH.matches(expected)) {
            return GitPushResult(GitResult(false, "'$expected' is not a full commit hash."))
        }
        if (cancellation.cancelled) return GitPushResult(GitResult(false, GitCli.CANCELLED), cancelled = true)
        val arguments =
            buildList {
                addAll(listOf("push", "--porcelain", "--recurse-submodules=no"))
                if (setUpstream) add("--set-upstream")
                if (force) add("--force-with-lease=$HEADS$target:${expected.orEmpty()}")
                addAll(listOf("--end-of-options", remote, "$HEADS$branch:$HEADS$target"))
            }
        val environment = fetchEnvironment(git.environment, git.sshConfigured(root) == true)
        val written =
            git.writeWithOutput(
                root,
                arguments,
                timeout = timeout,
                environment = environment,
                cancellation = cancellation,
            )
        if (written.exitValue == null && cancellation.cancelled) {
            return GitPushResult(GitResult(false, GitCli.CANCELLED), cancelled = true)
        }
        val status = status(written.output, "$HEADS$branch:$HEADS$target")
        if (written.result.success) {
            val result = GitResult(true, written.errors)
            return GitPushResult(result, upToDate = status?.flag == '=', created = status?.flag == '*')
        }
        val rejection = if (status?.flag == '!') rejection(status.summary) else null
        val explanation =
            when (rejection) {
                GitPushRejection.NON_FAST_FORWARD -> {
                    "$remote/$target has commits that your branch does not have. " +
                        "Fetch, then merge or rebase, or force push.\n"
                }

                GitPushRejection.STALE -> {
                    "$remote/$target changed since your last fetch. Fetch and check it before force pushing.\n"
                }

                else -> {
                    ""
                }
            }
        // Stopped while it waited for the server's answer, it may have pushed all the same, as a cancel may.
        val unknown = if (written.timedOut) "\nIt may have reached $remote. Fetch to check." else ""
        val result = written.result.copy(message = explanation + written.result.message + unknown)
        return GitPushResult(result, rejection = rejection)
    }

    /** A status line of `git push --porcelain`: `<flag> TAB <from>:<to> TAB <summary>`. */
    private class Status(
        val flag: Char,
        val summary: String,
    )

    private companion object {
        const val HEADS = "refs/heads/"

        val FULL_HASH = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

        /** The values of `push.default` that push to the upstream branch; `tracking` is its old name. */
        val UPSTREAM_MODES = setOf("upstream", "tracking")

        /** The `--glob` pattern matching what a fetch refspec writes: below a glob's prefix, or the exact ref. */
        fun glob(destination: RefspecDestination): String {
            if (destination.glob) return destination.prefix + "*"
            // A pattern without wildcard gets /* appended by git: a character class keeps it exact.
            return destination.prefix.dropLast(1) + "[" + destination.prefix.last() + "]"
        }

        /** Where [refspec] (`[+]<src>:<dst>`, a star in both or none) writes the ref [source]; `null` if it does not. */
        fun mapped(
            refspec: String,
            source: String,
        ): String? {
            val spec = refspec.trim().removePrefix("+")
            if (spec.startsWith('^') || ':' !in spec) return null
            val from = spec.substringBefore(':')
            val to = spec.substringAfter(':')
            if ('*' !in from) return to.takeIf { from == source && '*' !in it && it.startsWith("refs/") }
            val prefix = from.substringBefore('*')
            val suffix = from.substringAfter('*')
            if ('*' !in to || source.length < prefix.length + suffix.length) return null
            if (!source.startsWith(prefix) || !source.endsWith(suffix)) return null
            return to.replace("*", source.substring(prefix.length, source.length - suffix.length))
        }

        /** The status line of the refspec [refs] (`refs/heads/a:refs/heads/b`) in the porcelain [output]. */
        fun status(
            output: String,
            refs: String,
        ): Status? =
            output
                .lineSequence()
                .map { it.split('\t', limit = 3) }
                .firstOrNull { it.size == 3 && it[0].length == 1 && it[1] == refs }
                ?.let { Status(it[0][0], it[2]) }

        /** Why the remote refused a push: the reason, last in parentheses, of a rejected line's [summary]. */
        fun rejection(summary: String): GitPushRejection =
            when (summary.substringAfterLast('(', "").removeSuffix(")")) {
                "non-fast-forward", "fetch first" -> GitPushRejection.NON_FAST_FORWARD
                "stale info" -> GitPushRejection.STALE
                else -> GitPushRejection.OTHER
            }
    }
}
