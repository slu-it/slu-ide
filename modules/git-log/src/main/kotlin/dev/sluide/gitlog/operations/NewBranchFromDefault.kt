package dev.sluide.gitlog.operations

import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.GitLogWrites
import java.nio.file.Path

/** What the New Branch dialog after the quick action's fetch needs (ADR 0181). */
internal data class NewBranchPrompt(
    /** The full refname of the start point, e.g. `refs/remotes/origin/main`. */
    val startPoint: String,
    /** Its short name, e.g. `origin/main`. */
    val startLabel: String,
    /** Local branch short name to its commit. */
    val tips: Map<String, String>,
    /** The short name of the current branch, `null` when detached. */
    val current: String?,
)

/**
 * The header's quick action (ADR 0181): fetches [remote] (cancellable), resolves its default branch as
 * [RebaseOntoOriginDefault] does and gives the [NewBranchPrompt] the New Branch dialog needs. It creates nothing.
 */
internal data class PrepareNewBranchFromDefault(
    val remote: String = "origin",
) : RefOperation {
    override val progress get() = "Fetching $remote…"
    override val errorTitle get() = "New Branch Failed"
    override val failure get() = "A branch could not be created from $remote's default branch."

    // It changes no files; the CreateBranch after the dialog asks about unsaved editors.
    override val fileChange: String? get() = null
    override val cancellable get() = true
    override val contactsRemote get() = true

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ) = run(writes, root, RunContext())

    override fun run(
        writes: GitLogWrites,
        root: Path,
        context: RunContext,
    ): RefOutcome {
        val fetch = writes.fetch(root, remote, context.cancellation, context.ssh)
        val stopped = context.interrupted(fetch.cancelled, fetch.result)
        if (stopped) {
            return RefOutcome(GitResult(true, ""), "New branch cancelled.", cancelled = true)
        }
        val note = pullFetchNote(remote, fetch)
        if (!fetch.result.success) {
            return RefOutcome(
                GitResult(false, fetch.result.message),
                note,
                failure = "$remote could not be fetched; no branch was created.",
            )
        }
        context.uncancellable()
        val ref =
            writes.remoteDefaultBranch(root, remote)
                ?: return RefOutcome(
                    GitResult(false, "$remote's default branch could not be found (no $remote/HEAD, main or master)."),
                    note,
                    failure = failure,
                )
        val tips =
            writes.localBranchTips(root)
                ?: return RefOutcome(
                    GitResult(false, "The local branches could not be read."),
                    note,
                    failure = failure,
                )
        val prompt = NewBranchPrompt(ref, ref.removePrefix(REMOTES), tips, writes.currentBranch(root))
        return RefOutcome(GitResult(true, ""), note, newBranch = prompt)
    }
}
