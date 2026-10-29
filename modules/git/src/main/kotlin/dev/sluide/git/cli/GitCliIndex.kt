package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import dev.sluide.git.GitInProgress
import dev.sluide.git.GitOperation
import dev.sluide.git.GitPendingConflicts
import dev.sluide.git.GitPendingOperation
import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitPick
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The paths git lists with `git ls-files -z` and [option] in the repository at [root], relative to it, `/`-separated
 * and not quoted; `null` if git is not available, fails or takes longer than 10 s.
 */
private fun GitCli.listFiles(
    root: Path,
    vararg option: String,
): List<String>? {
    if (!available) return null
    val arguments = listOf("--no-optional-locks", "ls-files", "-z") + option
    return run(arguments, root, 10.seconds)?.split('\u0000')?.filter(String::isNotEmpty)
}

/** The paths in the index of the repository at [root] (ADR 0048); `null` on failure. */
internal fun GitCli.indexedPaths(root: Path): List<String>? = listFiles(root)

/** The untracked files, not ignored, one by one also inside untracked folders (ADR 0048); `null` on failure. */
internal fun GitCli.untrackedPaths(root: Path): List<String>? = listFiles(root, "--others", "--exclude-standard")

/**
 * Every file on disk that is not in the index, the ignored ones too, one by one also inside untracked and ignored
 * folders (which may be many: only for the few paths the JVM cannot name); `null` on failure.
 */
internal fun GitCli.otherPaths(root: Path): List<String>? = listFiles(root, "--others")

/**
 * The paths of the repository at [root] whose index differs from `HEAD` ([staged]) or whose working-tree file
 * differs from the index (not [staged]): `git diff [--cached HEAD] --name-only -z --no-renames`, each path of a
 * rename on its own, without unmerged paths (`--diff-filter=u`, their stages come from commits) and without
 * submodules that differ only by changes inside their checkout (`--ignore-submodules=dirty`); `null` if git is not
 * available, fails (also without a `HEAD` commit for [staged]) or takes longer than 10 s.
 */
internal fun GitCli.changedPaths(
    root: Path,
    staged: Boolean,
): Set<String>? {
    if (!available) return null
    val arguments =
        buildList {
            addAll(listOf("--no-optional-locks", "diff", "--name-only", "-z", "--no-renames", "--no-ext-diff"))
            addAll(listOf("--diff-filter=u", "--ignore-submodules=dirty"))
            if (staged) addAll(listOf("--cached", "HEAD"))
            add("--")
        }
    return run(arguments, root, 10.seconds)?.split('\u0000')?.filter(String::isNotEmpty)?.toSet()
}

/**
 * How the index of the repository at [root] differs from [commit] (a full hash): `git diff-index --cached --raw -z
 * --no-renames`, records `:<commit's mode> <index's mode> <object> <object> <status>` NUL `<path>` NUL, the status
 * `D` for a path only [commit] has, `A` for one only the index has, `U` for an unmerged one; `null` if git is not
 * available, fails or takes longer than 10 s.
 */
internal fun GitCli.indexChanges(
    root: Path,
    commit: String,
): String? {
    if (!available || commit.startsWith('-')) return null
    val arguments =
        listOf("--no-optional-locks", "diff-index", "--cached", "--raw", "-z", "--no-renames", commit, "--")
    return run(arguments, root, 10.seconds)
}

/**
 * The tree that merging [theirs] into [ours] (commits, or trees with git 2.44 or later) with [base] as their merge
 * base gives in the repository at [root], as `git cherry-pick` and `git revert` merge: `git merge-tree
 * --write-tree --merge-base=<base>` (git 2.40 or later), also with conflicts (their files with conflict markers).
 * It stores the merged files' objects, unreferenced, and changes neither the index nor files; merge drivers of the
 * configuration run, and a partial clone may fetch missing objects. `null` if git is not available, fails or takes
 * longer than 60 s.
 */
internal fun GitCli.mergeTree(
    root: Path,
    base: String,
    ours: String,
    theirs: String,
): String? {
    if (!available || listOf(base, ours, theirs).any { it.startsWith('-') }) return null
    val arguments =
        listOf(
            "--no-optional-locks",
            "merge-tree",
            "--write-tree",
            "--no-messages",
            "--merge-base=$base",
            ours,
            theirs,
        )
    val execution = execute(arguments, root, 60.seconds, null, write = false) ?: return null
    // 1: conflicts, with the tree all the same.
    if (execution.exitValue !in 0..1 || !execution.complete) return null
    return execution.output
        .toString(Charsets.UTF_8)
        .lineSequence()
        .first()
        .trim()
        .takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
}

/**
 * What git is in the middle of in the repository at [root] (ADR 0042): a rebase (`rebase-merge`, `rebase-apply`;
 * [pendingRebase], ADR 0063), `git am` (`rebase-apply/applying`), a merge (`MERGE_HEAD`; [pendingMerge], ADR 0105) or a
 * cherry-pick or revert sequence (`sequencer`), found at their `git rev-parse --git-path` (per worktree), else a single
 * cherry-pick or revert (`CHERRY_PICK_HEAD`, `REVERT_HEAD`) with the paths still unmerged, else unmerged paths alone
 * ([GitPendingConflicts], ADR 0107); `null` for nothing. A failure if git is not available or fails.
 */
internal fun GitCli.inProgress(
    root: Path,
    cancellation: GitCancellation? = null,
): Result<GitInProgress?> {
    val unreadable = Result.failure<GitInProgress?>(IllegalStateException("git could not be read"))
    if (!available) return unreadable
    val names = listOf("rebase-merge", "rebase-apply", "sequencer", "MERGE_HEAD", "MERGE_MSG")
    val (rebaseMerge, rebaseApply, sequencer, mergeHead, mergeMessage) =
        gitPaths(root, names, cancellation = cancellation) ?: return unreadable
    val applying = Files.exists(rebaseApply.resolve("applying"))
    val rebaseState =
        when {
            Files.isDirectory(rebaseMerge) -> rebaseMerge
            !applying && Files.isDirectory(rebaseApply) -> rebaseApply
            else -> null
        }
    if (rebaseState != null) {
        val rebase = pendingRebase(root, rebaseState, apply = rebaseState == rebaseApply, cancellation)
        return rebase?.let { Result.success(it) } ?: unreadable
    }
    if (!applying && Files.exists(mergeHead)) {
        return pendingMerge(root, mergeHead, mergeMessage, cancellation)?.let { Result.success(it) } ?: unreadable
    }
    val operation =
        when {
            applying -> GitOperation.AM
            Files.isDirectory(sequencer) -> sequenceOperation(sequencer.resolve("todo"))
            else -> null
        }
    if (operation != null) return Result.success(GitPendingOperation(operation))
    val pick =
        commitOfRevision(root, "CHERRY_PICK_HEAD", cancellation = cancellation)?.let { GitPick.CHERRY_PICK to it }
            ?: commitOfRevision(root, "REVERT_HEAD", cancellation = cancellation)?.let { GitPick.REVERT to it }
    val conflicts = unmergedPaths(root, cancellation = cancellation) ?: return unreadable
    return when {
        pick != null -> Result.success(GitPendingPick(pick.first, pick.second, conflicts))
        conflicts.isNotEmpty() -> Result.success(GitPendingConflicts(conflicts))
        else -> Result.success(null)
    }
}

/** A cherry-pick or a revert sequence, as the first instruction of its [todo] file says. */
private fun GitCli.sequenceOperation(todo: Path): GitOperation {
    val first =
        try {
            Files
                .readAllLines(todo)
                .map(String::trim)
                .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
        } catch (_: IOException) {
            null
        }
    return when (first?.substringBefore(' ')) {
        "pick", "p" -> GitOperation.CHERRY_PICK_SEQUENCE
        "revert" -> GitOperation.REVERT_SEQUENCE
        else -> GitOperation.SEQUENCE
    }
}

/**
 * Where the files [names] of the repository at [root] are: `git rev-parse --git-path`, which knows linked
 * worktrees; `null` if git is not available or fails, or if a path cannot be a path in the JVM's locale.
 */
internal fun GitCli.gitPaths(
    root: Path,
    names: List<String>,
    cancellation: GitCancellation? = null,
): List<Path>? {
    if (!available) return null
    val arguments = listOf("--no-optional-locks", "rev-parse") + names.flatMap { listOf("--git-path", it) }
    val lines =
        run(arguments, root, 3.seconds, cancellation = cancellation)?.lines()?.filter(String::isNotEmpty) ?: return null
    if (lines.size != names.size) return null
    return try {
        lines.map { root.resolve(it) }
    } catch (_: InvalidPathException) {
        null
    }
}

/**
 * The unmerged entries in the index of the repository at [root] (conflicts), one per stage, in the index's order:
 * `git ls-files -u -z`; `null` if git is not available, fails or takes longer than 10 s.
 */
internal fun GitCli.unmergedEntries(
    root: Path,
    cancellation: GitCancellation? = null,
): List<UnmergedEntry>? {
    if (!available) return null
    val output =
        run(
            listOf("--no-optional-locks", "ls-files", "--unmerged", "-z"),
            root,
            10.seconds,
            cancellation = cancellation,
        )
            ?: return null
    // <mode> <object> <stage>\t<path>
    return output.split('\u0000').filter { '\t' in it }.map { record ->
        val info = record.substringBefore('\t').split(' ')
        if (info.size != 3) return null
        UnmergedEntry(record.substringAfter('\t'), info[2].toIntOrNull() ?: return null, info[0], info[1])
    }
}

/** The paths of the [unmergedEntries], relative to [root], each once; `null` on failure. */
internal fun GitCli.unmergedPaths(
    root: Path,
    cancellation: GitCancellation? = null,
): List<String>? = unmergedEntries(root, cancellation = cancellation)?.map { it.path }?.distinct()

/**
 * The tracked paths of the repository at [root] whose file is missing from the working tree (deleted), also those
 * with unmerged entries: `git ls-files -d -z`; `null` if git is not available, fails or takes longer than 10 s.
 */
internal fun GitCli.missingFiles(root: Path): Set<String>? {
    if (!available) return null
    return run(listOf("--no-optional-locks", "ls-files", "--deleted", "-z"), root, 10.seconds)
        ?.split('\u0000')
        ?.filter(String::isNotEmpty)
        ?.toSet()
}

/**
 * The values of the git attributes [names] of the [paths] in the repository at [root] ("unspecified", "set",
 * "unset" or a value), by path and name: `git check-attr --stdin -z`, the paths on its standard input; `null` if git
 * is not available, fails or takes longer than 10 s.
 */
internal fun GitCli.attributes(
    root: Path,
    paths: List<String>,
    names: List<String>,
): Map<String, Map<String, String>>? {
    if (!available) return null
    if (paths.isEmpty()) return emptyMap()
    val input = paths.joinToString("") { "$it\u0000" }.toByteArray(Charsets.UTF_8)
    val arguments = listOf("--no-optional-locks", "check-attr", "--stdin", "-z") + names
    val output = runBytes(arguments, root, 10.seconds, input)?.toString(Charsets.UTF_8) ?: return null
    // <path> NUL <attribute> NUL <value> NUL
    val fields = output.split('\u0000')
    return (0 until fields.size / 3)
        .map { Triple(fields[it * 3], fields[it * 3 + 1], fields[it * 3 + 2]) }
        .groupBy({ it.first }, { it.second to it.third })
        .mapValues { (_, values) -> values.toMap() }
}

/**
 * Whether the index of the repository at [root] has the tree of `HEAD`: `git diff-index --cached --quiet HEAD`;
 * `null` if git is not available, fails (also without a `HEAD` commit) or takes longer than 10 s.
 */
internal fun GitCli.indexMatchesHead(root: Path): Boolean? {
    if (!available) return null
    val arguments = listOf("--no-optional-locks", "diff-index", "--cached", "--quiet", "HEAD", "--")
    return when (execute(arguments, root, 10.seconds, null, write = false)?.exitValue) {
        0 -> true
        1 -> false
        else -> null
    }
}

/** An unmerged index entry (a conflict): the [path]'s version of [stage] 1 (base), 2 (ours) or 3 (theirs). */
internal data class UnmergedEntry(
    val path: String,
    val stage: Int,
    val mode: String,
    val objectName: String,
)
