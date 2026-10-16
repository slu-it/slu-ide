package dev.sluide.git

import org.springframework.stereotype.Component
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.time.Duration

/**
 * What a git command that changes the repository gave: whether it [succeeded][success], and git's own [message].
 * [changed] tells that the change was made although git failed afterwards, e.g. a branch was checked out but a
 * post-checkout hook failed.
 */
data class GitResult(
    val success: Boolean,
    val message: String,
    val changed: Boolean = success,
)

/** The commit a local branch points at, as far as it could be read. */
sealed interface GitBranchTip {
    /** There is no such branch. */
    data object Missing : GitBranchTip

    /** The branch points at [commit] (a full hash). */
    data class At(
        val commit: String,
    ) : GitBranchTip

    /** Whether the branch exists and where it points could not be read (git failed). */
    data object Unknown : GitBranchTip
}

/**
 * What deleting branches gave: the [result] of `git branch -d` (or `-D`), and those of the branches asked for that are
 * still there afterwards, e.g. as git refuses to delete a branch that is not fully merged with `-d`.
 */
data class GitBranchDeletion(
    val result: GitResult,
    val notDeleted: List<String>,
)

/**
 * Changes the branches and tags of a repository and checks out (ADR 0041), with the git command line: hooks and the
 * user's configuration apply as for git itself. Every method runs git on the calling thread and gives git's own
 * message; a checkout may take 10 minutes (it changes files, filters such as LFS may be slow), anything else 60 s.
 * Names and revisions are passed as arguments, after `--end-of-options` (git 2.24 or later), so a name git would take
 * for an option or that the JVM cannot pass unchanged ([canPass]) is refused without running git.
 *
 * A new branch is created first and checked out second, never with `git switch -c` or `--track`: those change the
 * working tree and index before they create the branch, and if that fails (a name clashing with a ref, tracking that
 * cannot be set up) they leave the other commit's files staged on the old branch.
 */
@Component
class GitBranches(
    private val git: GitCli,
) {
    /**
     * Whether [argument] reaches git unchanged as a process argument: not if the JVM's locale cannot encode it (e.g.
     * non-ASCII characters in a POSIX locale, ADR 0038).
     */
    fun canPass(argument: String): Boolean = canPassArgument(argument)

    /** Checks out the local branch [branch] (its short name, `main`): `git switch`. */
    fun switchBranch(
        root: Path,
        branch: String,
    ): GitResult = switchTo(root, branch)

    /**
     * Checks out [revision] (a hash or full refname) with a detached `HEAD`: `git switch --detach`. A failure after which
     * `HEAD` is detached at its commit anyway (a failing post-checkout hook) is [GitResult.changed].
     */
    fun switchDetached(
        root: Path,
        revision: String,
    ): GitResult {
        val switched = checkout(root, listOf(revision), listOf("switch", "--detach", "--end-of-options", revision))
        if (switched.success) return switched
        val target = git.commitOfRevision(root, revision) ?: return switched
        val detachedThere = git.currentBranch(root) == null && git.headCommit(root) == target
        return if (detachedThere) switched.copy(changed = true) else switched
    }

    /**
     * Creates a local branch tracking the remote branch [remoteBranch] (its full refname, `refs/remotes/origin/fix`),
     * named as it without the remote (`fix`, as `git switch --track` would name it): `git branch --track`, then checks
     * it out (`git switch`). If either fails, the new branch is deleted again (see [createBranch]).
     */
    fun switchTracking(
        root: Path,
        remoteBranch: String,
    ): GitResult {
        val local = remoteBranch.removePrefix("refs/remotes/").substringAfter('/')
        return create(root, local, remoteBranch, listOf("--track"), checkout = true)
    }

    /**
     * The commit the local branch [name] (short name) points at, [GitBranchTip.Missing] if there is none,
     * [GitBranchTip.Unknown] if git fails (or the name cannot be passed to it).
     */
    fun branchTip(
        root: Path,
        name: String,
    ): GitBranchTip = if (refusal(name) == null) git.branchTip(root, name) else GitBranchTip.Unknown

    /**
     * Whether the commit [ancestor] is an ancestor of [descendant] or the same (hashes or full refnames): `git
     * merge-base --is-ancestor`; `null` if git fails.
     */
    fun isAncestor(
        root: Path,
        ancestor: String,
        descendant: String,
    ): Boolean? =
        if (refusal(ancestor) == null &&
            refusal(descendant) == null
        ) {
            git.isAncestor(root, ancestor, descendant)
        } else {
            null
        }

    /** Whether the local branch [name] (its short name) exists; `false` if git fails. */
    fun branchExists(
        root: Path,
        name: String,
    ): Boolean =
        git
            .localBranches(root)
            ?.lineSequence()
            ?.any { it == "refs/heads/$name" } == true

    /**
     * Creates the branch [name] at [startPoint] (a hash or full refname), moving an existing one if [overwrite]: `git
     * branch` (`-f`); git refuses to move the checked-out branch. If [checkout], then checks it out (`git switch`).
     *
     * Where the branch pointed before is read first; if that fails, nothing is done. If git fails after all the same
     * (the branch was written, but tracking could not be set up; or the checkout failed), a branch that did not exist
     * before is deleted again and a moved one moved back to its commit (not to an upstream `branch.autoSetupMerge`
     * may have changed); never is an existing branch deleted. If that fails too, the message says so.
     */
    fun createBranch(
        root: Path,
        name: String,
        startPoint: String,
        checkout: Boolean,
        overwrite: Boolean,
    ): GitResult = create(root, name, startPoint, if (overwrite) listOf("-f") else emptyList(), checkout)

    /** `git branch <options> <name> <startPoint>`, then `git switch <name>` if [checkout], undone on failure. */
    private fun create(
        root: Path,
        name: String,
        startPoint: String,
        options: List<String>,
        checkout: Boolean,
    ): GitResult {
        listOf(name, startPoint).firstNotNullOfOrNull(::refusal)?.let { return GitResult(false, it) }
        val before = git.branchTip(root, name)
        if (before == GitBranchTip.Unknown) return GitResult(false, "The commit of branch '$name' could not be read.")
        // Overwriting a branch that is not there means nothing; with -f, on a case-insensitive file system, git would
        // write through to a branch whose name differs only in case (Feature for feature), which an undo would delete.
        val effective = if (before is GitBranchTip.At) options else options - "-f"
        val created =
            write(
                root,
                listOf(name, startPoint),
                listOf("branch") + effective + listOf("--end-of-options", name, startPoint),
            )
        if (!created.success) {
            // Git may have written the branch before failing.
            val after = git.branchTip(root, name)
            if (after == GitBranchTip.Unknown) {
                return created.withProblem("Whether branch $name was created could not be read.")
            }
            val changed = after is GitBranchTip.At && after != before
            return if (changed) created.withProblem(undo(root, name, before)) else created
        }
        if (!checkout) return created
        val switched = switchTo(root, name)
        if (switched.success || switched.changed) return switched
        return switched.withProblem(undo(root, name, before))
    }

    /**
     * Deletes the branch [name] if it was [GitBranchTip.Missing] before, or moves it back to its commit then; what went
     * wrong with that, or `null`.
     */
    private fun undo(
        root: Path,
        name: String,
        before: GitBranchTip,
    ): String? =
        when (before) {
            GitBranchTip.Missing -> {
                write(root, listOf(name), listOf("branch", "-D", "--end-of-options", name))
                    .takeIf { !it.success }
                    ?.let { "The new branch $name could not be deleted again: ${it.message}" }
            }

            is GitBranchTip.At -> {
                write(root, listOf(name), listOf("branch", "-f", "--end-of-options", name, before.commit))
                    .takeIf { !it.success }
                    ?.let { "Branch $name could not be moved back to ${before.commit.take(SHORT_HASH)}: ${it.message}" }
            }

            // Nothing is done then (see create).
            GitBranchTip.Unknown -> {
                null
            }
        }

    /**
     * Checks out the local branch [name]; a failure after which `HEAD` is on it anyway (a failing post-checkout hook)
     * is [GitResult.changed].
     */
    private fun switchTo(
        root: Path,
        name: String,
    ): GitResult {
        val switched = checkout(root, listOf(name), listOf("switch", "--end-of-options", name))
        if (switched.success) return switched
        return if (git.currentBranch(root) == "refs/heads/$name") switched.copy(changed = true) else switched
    }

    private fun GitResult.withProblem(problem: String?) =
        if (problem ==
            null
        ) {
            this
        } else {
            copy(message = "$message\n$problem")
        }

    /**
     * Creates the tag [name] at [commit]: lightweight without a [message] (`null` or blank), else annotated with it
     * (`git tag -a -F -`), the message given on git's standard input as UTF-8 and cleaned up as `--cleanup=whitespace`
     * does: leading and trailing blank lines and trailing whitespace are removed and consecutive blank lines collapsed,
     * lines starting with `#` are kept.
     */
    fun createTag(
        root: Path,
        name: String,
        commit: String,
        message: String?,
    ): GitResult {
        val names = listOf(name, commit)
        if (message.isNullOrBlank()) return write(root, names, listOf("tag", "--end-of-options", name, commit))
        val arguments = listOf("tag", "-a", "--cleanup=whitespace", "-F", "-", "--end-of-options", name, commit)
        return write(root, names, arguments, message.toByteArray(Charsets.UTF_8))
    }

    /** Renames the local branch [old] to [new] (short names): `git branch -m`. */
    fun renameBranch(
        root: Path,
        old: String,
        new: String,
    ): GitResult = write(root, listOf(old, new), listOf("branch", "-m", "--end-of-options", old, new))

    /**
     * Deletes the local branches [names] (short names): `git branch -d`, which refuses branches that are not fully
     * merged, or with [force] `-D`. Git goes on after a branch it refuses; the result lists the branches still there.
     */
    fun deleteBranches(
        root: Path,
        names: List<String>,
        force: Boolean,
    ): GitBranchDeletion {
        val result = write(root, names, listOf("branch", if (force) "-D" else "-d", "--end-of-options") + names)
        if (result.success) return GitBranchDeletion(result, emptyList())
        val existing =
            git
                .localBranches(root)
                ?.lines()
                ?.toSet()
                .orEmpty()
        return GitBranchDeletion(result, names.filter { "refs/heads/$it" in existing })
    }

    /** Deletes the tags [names] (short names): `git tag -d`. */
    fun deleteTags(
        root: Path,
        names: List<String>,
    ): GitResult = write(root, names, listOf("tag", "-d", "--end-of-options") + names)

    /** Runs git with [arguments] unless one of the [names] cannot be passed as an argument (see [refusal]). */
    private fun write(
        root: Path,
        names: List<String>,
        arguments: List<String>,
        input: ByteArray? = null,
    ): GitResult = run(root, names, arguments, input, GitCli.WRITE_TIMEOUT)

    /** [write] for a checkout, which may take longer. */
    private fun checkout(
        root: Path,
        names: List<String>,
        arguments: List<String>,
    ): GitResult = run(root, names, arguments, null, GitCli.CHECKOUT_TIMEOUT)

    private fun run(
        root: Path,
        names: List<String>,
        arguments: List<String>,
        input: ByteArray?,
        timeout: Duration,
    ): GitResult {
        names.firstNotNullOfOrNull(::refusal)?.let { return GitResult(false, it) }
        return git.write(root, arguments, input, timeout)
    }

    private companion object {
        /** The length of hashes in messages, as in the git log. */
        const val SHORT_HASH = 8
    }
}

/**
 * Why [name] cannot be given to git as an argument, or `null` if it can: empty, starting with `-` (an option to git)
 * or with characters [charset], the JVM's for arguments, cannot encode.
 */
internal fun refusal(
    name: String,
    charset: Charset = argumentCharset,
): String? =
    when {
        name.isEmpty() -> "An empty name cannot be passed to git."
        name.startsWith('-') -> "'$name' cannot be passed to git: it starts with '-'."
        !canPassArgument(name, charset) -> "'$name' cannot be passed to git in this locale."
        else -> null
    }
