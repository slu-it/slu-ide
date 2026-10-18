package dev.sluide.git.cli

import dev.sluide.git.GitBranchTip
import dev.sluide.git.parse.GitRefsParser
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The output of `git for-each-ref` for the branches, remote branches and tags of the repository at [root] (see
 * [GitRefsParser]), or `null` if git is not available, fails or takes longer than 10 s.
 */
internal fun GitCli.refs(root: Path): String? {
    if (!available) return null
    val arguments =
        listOf(
            "--no-optional-locks",
            "for-each-ref",
            // %(*objectname): the commit an annotated tag points at, empty for other refs; %(symref): the target
            // of a symbolic ref such as refs/remotes/origin/HEAD, which is skipped. Records end with a newline.
            "--format=%(objectname)%1f%(*objectname)%1f%(refname)%1f%(symref)",
            "refs/heads",
            "refs/remotes",
            "refs/tags",
        )
    return run(arguments, root, 10.seconds)
}

/**
 * The full refnames of the local branches of the repository at [root], one per line (`refs/heads/main`); `null`
 * if git is not available, fails or takes longer than 3 s.
 */
internal fun GitCli.localBranches(root: Path): String? {
    if (!available) return null
    return run(listOf("--no-optional-locks", "for-each-ref", "--format=%(refname)", "refs/heads"), root, 3.seconds)
}

/**
 * The objects the refs starting with [prefixes] (such as `refs/tags/`) of the repository at [root] point at, by
 * full refname, without symbolic refs (`refs/remotes/origin/HEAD`); `null` if git is not available, fails or takes
 * longer than 10 s.
 */
internal fun GitCli.refTips(
    root: Path,
    prefixes: List<String>,
): Map<String, String>? {
    if (!available) return null
    val arguments =
        listOf("--no-optional-locks", "for-each-ref", "--format=%(refname)%1f%(objectname)%1f%(symref)") + prefixes
    val output = run(arguments, root, 10.seconds) ?: return null
    return output
        .lineSequence()
        .map { it.split('\u001f') }
        .filter { it.size == 3 && it[2].isEmpty() }
        .associate { it[0] to it[1] }
}

/**
 * The commit the local branch [name] (short name) points at, [GitBranchTip.Missing] if there is no such branch, or
 * [GitBranchTip.Unknown] if git is not available, fails or takes longer than 3 s: `git for-each-ref`, which succeeds
 * without output for a missing branch.
 */
internal fun GitCli.branchTip(
    root: Path,
    name: String,
): GitBranchTip {
    if (!available) return GitBranchTip.Unknown
    val ref = "refs/heads/$name"
    // The pattern also matches the branches in a folder of that name (refs/heads/<name>/…): only the exact one counts.
    val arguments = listOf("--no-optional-locks", "for-each-ref", "--format=%(refname)%1f%(objectname)", ref)
    val output = run(arguments, root, 3.seconds) ?: return GitBranchTip.Unknown
    val commit =
        output
            .lineSequence()
            .map { it.split('\u001f') }
            .firstOrNull { it.size == 2 && it[0] == ref }
            ?.get(1)
    return if (commit == null) GitBranchTip.Missing else GitBranchTip.At(commit)
}

/**
 * The full object name of the commit `HEAD` points at in the repository at [root]; `null` if there is none yet
 * (an unborn branch), or if git is not available, fails or takes longer than 3 s.
 */
internal fun GitCli.headCommit(root: Path): String? = commitOfRevision(root, "HEAD")

/**
 * The full object name of the commit [revision] (`HEAD`, a hash or a full refname, also of an annotated tag) names
 * in the repository at [root]; `null` if it names none, or if git is not available, fails or takes longer than 3 s.
 */
internal fun GitCli.commitOfRevision(
    root: Path,
    revision: String,
): String? {
    if (!available || revision.startsWith('-')) return null
    // -q: an unborn HEAD or a missing revision fails quietly instead of with an error message.
    val arguments = listOf("--no-optional-locks", "rev-parse", "--verify", "-q", "$revision^{commit}")
    return run(arguments, root, 3.seconds)?.trim()?.ifEmpty { null }
}

/**
 * The full refname of the branch `HEAD` is attached to (`refs/heads/main`, also if it has no commits yet); `null`
 * when `HEAD` is detached, or if git is not available, fails or takes longer than 3 s.
 */
internal fun GitCli.currentBranch(root: Path): String? {
    if (!available) return null
    // -q: a detached HEAD fails quietly instead of with an error message.
    return run(listOf("--no-optional-locks", "symbolic-ref", "-q", "HEAD"), root, 3.seconds)?.trim()?.ifEmpty {
        null
    }
}

/**
 * The parents of [commit] (a full hash) in the repository at [root], full hashes in git's order: `git rev-list
 * --parents -n 1`; empty for a root commit, `null` if git is not available, fails or takes longer than 3 s.
 */
internal fun GitCli.parents(
    root: Path,
    commit: String,
): List<String>? {
    if (!available || commit.startsWith('-')) return null
    val arguments = listOf("--no-optional-locks", "rev-list", "--parents", "-n", "1", commit, "--")
    val fields = run(arguments, root, 3.seconds)?.trim()?.split(' ') ?: return null
    return if (fields.firstOrNull() == commit) fields.drop(1) else null
}

/**
 * The full message of the commit `HEAD` (`git show -s --format=%B`, as stored, with the newline git adds); `null` if
 * there is no commit, or if git is not available, fails or takes longer than 3 s.
 */
internal fun GitCli.headMessage(root: Path): String? {
    if (!available) return null
    val arguments =
        listOf(
            "--no-optional-locks",
            "show",
            "-s",
            "--no-color",
            // No gpg lines (log.showSignature), and UTF-8 whatever i18n.logOutputEncoding says.
            "--no-show-signature",
            "--encoding=UTF-8",
            "--format=%B",
            "HEAD",
            "--",
        )
    return run(arguments, root, 3.seconds)
}

/**
 * Whether `HEAD` of the repository at [root] is unborn: a symbolic ref to a branch without commits (`git rev-parse
 * -q --verify` exits with 1). `false` if there is a commit; `null` if git is not available or fails in any other
 * way, or `HEAD` is detached without a commit.
 */
internal fun GitCli.isHeadUnborn(root: Path): Boolean? {
    if (!available) return null
    val arguments = listOf("--no-optional-locks", "rev-parse", "-q", "--verify", "HEAD^{commit}")
    return when (execute(arguments, root, 3.seconds, null, write = false)?.exitValue) {
        0 -> false
        1 -> if (currentBranch(root) != null) true else null
        else -> null
    }
}

/**
 * The full refname of the branch `HEAD` is on in the repository at [root], an empty string when it is detached;
 * `null` if git is not available, fails or takes longer than 3 s (unlike [currentBranch], which gives `null` for
 * both).
 */
internal fun GitCli.headBranch(root: Path): String? {
    if (!available) return null
    val execution =
        execute(listOf("--no-optional-locks", "symbolic-ref", "-q", "HEAD"), root, 3.seconds, null, write = false)
            ?: return null
    if (!execution.complete) return null
    return when (execution.exitValue) {
        0 -> {
            execution.output
                .toString(Charsets.UTF_8)
                .trim()
                .ifEmpty { null }
        }

        1 -> {
            ""
        }

        else -> {
            null
        }
    }
}

/**
 * How many commits reachable from `HEAD` in the repository at [root] are not reachable from [target] (a full
 * hash) nor from any local branch other than [branch] (the short name of the current one, `null` when `HEAD` is
 * detached), remote branch or tag: `git rev-list --count`. `null` if git is not available, fails or takes longer
 * than 10 s.
 */
internal fun GitCli.unreachableCount(
    root: Path,
    target: String,
    branch: String?,
): Int? {
    if (!available || target.startsWith('-')) return null
    val arguments =
        buildList {
            addAll(listOf("--no-optional-locks", "rev-list", "--count", "HEAD", "--not", target))
            // A pattern for the next --branches, without refs/heads/ (with it, nothing would be excluded).
            if (branch != null) add("--exclude=$branch")
            addAll(listOf("--branches", "--tags", "--remotes", "--"))
        }
    return run(arguments, root, 10.seconds)?.trim()?.toIntOrNull()
}

/**
 * How many commits reachable from `HEAD` in the repository at [root] are reachable neither from [target] (a hash or
 * full refname) nor from any ref below `refs/` (branches, tags, remote branches, the stash, notes): the commits a
 * switch from a detached `HEAD` to [target] leaves behind, as git counts them for its warning; `git rev-list --count`.
 * `null` if git is not available, fails or takes longer than 10 s.
 */
internal fun GitCli.leftBehindCount(
    root: Path,
    target: String,
): Int? {
    if (!available || target.startsWith('-')) return null
    // A glob with *, so git appends no /*; it matches across slashes, as git's warning excludes every ref.
    val arguments =
        listOf("--no-optional-locks", "rev-list", "--count", "HEAD", "--not", target, "--glob=refs/*", "--")
    return run(arguments, root, 10.seconds)?.trim()?.toIntOrNull()
}

/**
 * The last commit, in topological order, that `HEAD` has and no ref matching [excludedGlobs] (`--glob`) has, as its
 * hash followed by its parents; empty if there is none. `null` if git is not available, fails or takes longer than
 * 10 s. Only the last line of git's output is read as a commit.
 */
internal fun GitCli.lastOutgoing(
    root: Path,
    excludedGlobs: List<String>,
): List<String>? {
    if (!available) return null
    val arguments =
        listOf("--no-optional-locks", "rev-list", "--topo-order", "--parents", "HEAD", "--not") +
            excludedGlobs.map { "--glob=$it" } + "--"
    val output = run(arguments, root, 10.seconds) ?: return null
    val last = output.trimEnd().substringAfterLast('\n')
    return last.split(' ').filter(String::isNotEmpty)
}

/**
 * The best common ancestor of the commits [first] and [second] (full hashes or refnames) in the repository at
 * [root]: `git merge-base`; `null` if they have none, or if git is not available, fails or takes longer than 10 s.
 */
internal fun GitCli.mergeBase(
    root: Path,
    first: String,
    second: String,
): String? {
    if (!available) return null
    val arguments = listOf("--no-optional-locks", "merge-base", "--end-of-options", first, second)
    return run(arguments, root, 10.seconds)?.trim()?.ifEmpty { null }
}

/**
 * Whether the commit [ancestor] is an ancestor of (or the same as) [descendant] in the repository at [root] (full
 * hashes or refnames): `git merge-base --is-ancestor`; `null` if git is not available, fails or takes longer than
 * 10 s.
 */
internal fun GitCli.isAncestor(
    root: Path,
    ancestor: String,
    descendant: String,
): Boolean? {
    if (!available) return null
    val arguments =
        listOf("--no-optional-locks", "merge-base", "--is-ancestor", "--end-of-options", ancestor, descendant)
    return when (execute(arguments, root, 10.seconds, null, write = false)?.exitValue) {
        0 -> true
        1 -> false
        else -> null
    }
}
