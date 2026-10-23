package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.comparisonCounts
import dev.sluide.git.cli.configValues
import dev.sluide.git.cli.fetchEnvironment
import dev.sluide.git.cli.headCommit
import dev.sluide.git.cli.lastOutgoing
import dev.sluide.git.cli.log
import dev.sluide.git.cli.mergeBase
import dev.sluide.git.cli.refTips
import dev.sluide.git.cli.refusal
import dev.sluide.git.cli.remoteNames
import dev.sluide.git.cli.sshConfigured
import dev.sluide.git.cli.status
import dev.sluide.git.parse.GitLogParser
import dev.sluide.git.parse.RefspecDestination
import dev.sluide.git.parse.refspecDestinations
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Pushes a branch, tells what a push would send (ADR 0049) and deletes refs on a remote (ADR 0078) with the git
 * command line. A push runs as a fetch does (ADR 0043): git never asks for anything ([fetchEnvironment]), it stops
 * after 10 minutes or on a cancel, and names that cannot be passed to git ([refusal]) fail without running it. Runs git
 * on the calling thread.
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
        cancellation: GitCancellation? = null,
    ): GitPushTarget? {
        val names = git.remoteNames(root, cancellation = cancellation)?.takeIf { it.isNotEmpty() } ?: return null
        val pushRemote = lastSetting(root, "branch.$branch.pushRemote", cancellation = cancellation)
        val pushDefault = lastSetting(root, "remote.pushDefault", cancellation = cancellation)
        val configuredRemote = lastSetting(root, "branch.$branch.remote", cancellation = cancellation)
        val merge = lastSetting(root, "branch.$branch.merge", cancellation = cancellation)
        val mode = lastSetting(root, "push.default", cancellation = cancellation)
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
        cancellation: GitCancellation? = null,
    ): Setting? =
        git.configValues(root, key, cancellation = cancellation)?.let {
            Setting(it.lastOrNull(String::isNotEmpty))
        }

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
        cancellation: GitCancellation? = null,
    ): String {
        val source = HEADS + branch
        val refspecs = git.configValues(root, "remote.$remote.fetch", cancellation = cancellation).orEmpty()
        return refspecs.firstNotNullOfOrNull { mapped(it, source) } ?: "refs/remotes/$remote/$branch"
    }

    /**
     * The branch of [remote] (`refs/heads/<branch>`) that git keeps in the remote-tracking ref [trackingRef] (a full
     * refname such as `refs/remotes/origin/fix`), the inverse of [trackingRef]: the first of `remote.<remote>.fetch`
     * that maps a branch there (ADR 0078). `null` if none does (also for a remote without fetch refspecs: nothing says
     * where the ref came from), the remote cannot be passed to git ([refusal]) or git fails.
     */
    fun remoteBranch(
        root: Path,
        remote: String,
        trackingRef: String,
        cancellation: GitCancellation? = null,
    ): String? {
        if (refusal(remote) != null) return null
        val refspecs = git.configValues(root, "remote.$remote.fetch", cancellation = cancellation) ?: return null
        return refspecs
            .firstNotNullOfOrNull { refspec -> reversed(refspec)?.let { mapped(it, trackingRef) } }
            ?.takeIf { it.startsWith(HEADS) && it.length > HEADS.length }
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
        cancellation: GitCancellation? = null,
    ): GitOutgoing? {
        require(limit > 0) { "limit must be positive: $limit" }
        if (refusal(remote) != null || refusal(branch) != null) return null
        if (git.headCommit(root, cancellation = cancellation) == null) return null
        val ref = trackingRef(root, remote, branch, cancellation = cancellation)
        val remoteCommit =
            git
                .refTips(
                    root,
                    listOf(ref.substringBeforeLast('/') + "/"),
                    cancellation = cancellation,
                )?.get(ref)
        val tracked = remoteCommit != null
        val destinations = destinations(root, remote, cancellation = cancellation)
        val globs = if (tracked) emptyList() else destinations.map(::glob)
        val filter =
            GitLogFilter(
                revisions = listOf("HEAD"),
                excluded = if (tracked) listOf(ref) else emptyList(),
                order = GitLogOrder.TOPOLOGICAL,
            )
        val listed =
            git.log(root, filter, 0, limit + 1, globs, cancellation = cancellation)?.let(GitLogParser::parse)
                ?: return null
        val commits = listed.take(limit)
        val more = listed.size > limit
        val behind =
            if (tracked) {
                git.comparisonCounts(root, "HEAD", ref, cancellation = cancellation)?.second
                    ?: return null
            } else {
                0
            }
        val base =
            when {
                commits.isEmpty() -> {
                    null
                }

                tracked -> {
                    git.mergeBase(root, "HEAD", ref, cancellation = cancellation)
                }

                else -> {
                    newBranchBase(
                        root,
                        destinations,
                        globs,
                        if (more) null else commits.last(),
                        cancellation = cancellation,
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
        cancellation: GitCancellation? = null,
    ): Result<String?> {
        val unreadable = Result.failure<String?>(IllegalStateException("git could not be read"))
        val folders = destinations.map { it.prefix.substringBeforeLast('/') + "/" }.distinct()
        val tips = git.refTips(root, folders, cancellation = cancellation) ?: return unreadable
        if (tips.keys.none { name -> destinations.any { it.matches(name) } }) return Result.success(null)
        if (last != null) return Result.success(last.parents.firstOrNull())
        val oldest = git.lastOutgoing(root, globs, cancellation = cancellation) ?: return unreadable
        return Result.success(oldest.getOrNull(1))
    }

    /** Where the fetch refspecs of [remote] write; `refs/remotes/<remote>/` if it has none. */
    private fun destinations(
        root: Path,
        remote: String,
        cancellation: GitCancellation? = null,
    ): List<RefspecDestination> =
        git
            .configValues(root, "remote.$remote.fetch", cancellation)
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

    /**
     * Deletes the [refs] on [remote] in one push (ADR 0078): `git push --porcelain --recurse-submodules=no
     * [--force-with-lease=<ref>:<expected>…] --end-of-options <remote> :<ref>…`, with the environment and the limits of
     * [push]: git never asks, it stops after the [timeout] or on a [cancellation], and names that cannot be passed to
     * git ([refusal]), refs not below `refs/` and an expected value that is no full hash fail without running it. A
     * branch is deleted only if the remote has it at its [GitRemoteDeletion.expected] commit (the lease); a tag has no
     * lease, and one the remote does not have counts as deleted, as git reports it. The refs are deleted one by one, so
     * some may be and others not: the result tells for each ref what became of it, read from git's report. Git reports
     * a failed lease alike for a branch that moved and one that is gone; only for those `git ls-remote` asks the push
     * URL of the remote which it is (with the same environment and cancellation, in the time left of the [timeout], which
     * covers both), and if that fails, it stays open.
     */
    fun deleteRemoteRefs(
        root: Path,
        remote: String,
        refs: List<GitRemoteDeletion>,
        cancellation: GitCancellation,
        timeout: Duration = GitCli.FETCH_TIMEOUT,
    ): GitRemoteDeletionResult {
        require(refs.isNotEmpty()) { "nothing to delete" }
        val names = listOf(remote) + refs.map { it.ref } + refs.mapNotNull { it.expected }
        names.firstNotNullOfOrNull { refusal(it) }?.let { return GitRemoteDeletionResult(GitResult(false, it)) }
        refs.firstOrNull { !it.ref.startsWith("refs/") }?.let {
            return GitRemoteDeletionResult(GitResult(false, "'${it.ref}' is no full ref name."))
        }
        refs.firstOrNull { it.expected != null && !FULL_HASH.matches(it.expected) }?.let {
            return GitRemoteDeletionResult(GitResult(false, "'${it.expected}' is not a full commit hash."))
        }
        if (cancellation.cancelled) return GitRemoteDeletionResult(GitResult(false, GitCli.CANCELLED), cancelled = true)
        val started = System.nanoTime()
        val arguments =
            buildList {
                addAll(listOf("push", "--porcelain", "--recurse-submodules=no"))
                refs.forEach { ref -> ref.expected?.let { add("--force-with-lease=${ref.ref}:$it") } }
                addAll(listOf("--end-of-options", remote))
                refs.forEach { add(":${it.ref}") }
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
            return GitRemoteDeletionResult(GitResult(false, GitCli.CANCELLED), cancelled = true)
        }
        val outcomes = LinkedHashMap<String, GitRemoteRefResult>()
        for (ref in refs) {
            val status = deletionStatus(written.output, ref.ref) ?: continue
            outcomes[ref.ref] =
                when {
                    status.flag == '-' -> {
                        GitRemoteRefResult(GitRemoteDeletionOutcome.DELETED)
                    }

                    status.flag == '!' && rejection(status.summary) == GitPushRejection.STALE -> {
                        GitRemoteRefResult(GitRemoteDeletionOutcome.STALE, status.summary)
                    }

                    else -> {
                        GitRemoteRefResult(GitRemoteDeletionOutcome.REJECTED, status.summary)
                    }
                }
        }
        val stale = outcomes.filterValues { it.outcome == GitRemoteDeletionOutcome.STALE }.keys
        if (stale.isNotEmpty()) {
            // Within the one timeout of the whole deletion.
            val left = timeout - (System.nanoTime() - started).nanoseconds
            val existing = remoteRefs(root, remote, stale.toList(), environment, cancellation, left)
            for (ref in stale) {
                val outcome =
                    when {
                        existing == null -> GitRemoteDeletionOutcome.STALE_OR_MISSING
                        ref in existing -> GitRemoteDeletionOutcome.STALE
                        else -> GitRemoteDeletionOutcome.MISSING
                    }
                outcomes[ref] = outcomes.getValue(ref).copy(outcome = outcome)
            }
        }
        if (written.result.success) return GitRemoteDeletionResult(GitResult(true, written.errors), outcomes = outcomes)
        // Stopped while it waited for the server's answer, it may have deleted all the same, as a cancel may.
        val unknown = if (written.timedOut) "\nIt may have reached $remote. Fetch to check." else ""
        val result = written.result.copy(message = written.result.message + unknown)
        return GitRemoteDeletionResult(result, outcomes = outcomes)
    }

    /**
     * Which of the [refs] (full refnames) [remote] has, as `git ls-remote --end-of-options <url> <ref>…` lists them at
     * the URL a push of [remote] goes to (`git remote get-url --push`, which may differ from the one fetched from),
     * within the [timeout] left; `null` if that URL cannot be read or passed to git ([refusal]), or `ls-remote` fails,
     * is cancelled or times out.
     */
    private fun remoteRefs(
        root: Path,
        remote: String,
        refs: List<String>,
        environment: Map<String, String>,
        cancellation: GitCancellation,
        timeout: Duration,
    ): Set<String>? {
        if (!timeout.isPositive()) return null
        val url =
            git
                .run(
                    listOf("remote", "get-url", "--push", "--end-of-options", remote),
                    root,
                    minOf(timeout, URL_TIMEOUT),
                    cancellation,
                )?.lineSequence()
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { refusal(it) == null }
                ?: return null
        val listed =
            git.writeWithOutput(
                root,
                listOf("ls-remote", "--end-of-options", url) + refs,
                timeout = timeout,
                environment = environment,
                cancellation = cancellation,
            )
        if (!listed.result.success) return null
        // Patterns match the end of a name: only the exact names count.
        return listed.output
            .lineSequence()
            .mapNotNull { line -> line.split('\t', limit = 2).getOrNull(1) }
            .filterTo(HashSet()) { it in refs }
    }

    /** A status line of `git push --porcelain`: `<flag> TAB <from>:<to> TAB <summary>`. */
    private class Status(
        val flag: Char,
        val summary: String,
    )

    private companion object {
        const val HEADS = "refs/heads/"

        val FULL_HASH = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

        /** How long reading the push URL of a remote may take; a read of the configuration. */
        val URL_TIMEOUT = 3.seconds

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

        /** [refspec] with source and destination swapped, to map back ([remoteBranch]); `null` for a negative one. */
        fun reversed(refspec: String): String? {
            val spec = refspec.trim().removePrefix("+")
            if (spec.startsWith('^') || ':' !in spec) return null
            return spec.substringAfter(':') + ":" + spec.substringBefore(':')
        }

        /**
         * The status line of deleting [ref] in the porcelain [output]: `:<ref>`, or `(delete):<ref>` when git refused it
         * itself (a failed lease).
         */
        fun deletionStatus(
            output: String,
            ref: String,
        ): Status? = status(output, ":$ref") ?: status(output, "(delete):$ref")

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
