package dev.sluide.gitlog.operations

import dev.sluide.git.GitBranchTip
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefNames
import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.GitLogWrites
import dev.sluide.gitlog.data.SHORT_HASH
import javafx.scene.control.ButtonType
import java.nio.file.Path

/**
 * What an operation that changes the repository gave (ADR 0041): git's [result] (a failure is shown in an error
 * dialog), what the header says afterwards ([done], `null` if nothing was done), the branches git refused to delete
 * without force ([refused], with git's message [refusedMessage]), a question to ask before the operation may run after
 * all ([confirmation]; nothing was done then), a cherry-pick or revert git stopped and left for the user ([stopped],
 * ADR 0042), a rebase ([rebaseStopped], ADR 0063) or a merge ([mergeStopped], ADR 0105), the message of a commit to
 * edit ([reword], ADR 0100), what to ask before a pull ([pull], ADR 0106), and the question above git's message in the
 * error dialog if it is not the operation's ([failure], e.g. naming the remotes that could not be fetched, ADR 0043),
 * and whether it was [cancelled] before it was done (a fetch, commit or push). For stashes (ADR 0107): what the Stash
 * Changes dialog asks ([stashPrompt]), the conflicts a pop or apply gave ([stashConflicts]) and the operation to start
 * next, asking about unsaved editors first ([next]: the pop of the stash Pop Latest Stash read).
 */
internal data class RefOutcome(
    val result: GitResult,
    val done: String?,
    val refused: List<String> = emptyList(),
    val refusedMessage: String = "",
    val confirmation: RefConfirmation? = null,
    val stopped: PickStop? = null,
    val rebaseStopped: RebaseStop? = null,
    val mergeStopped: MergeStop? = null,
    val reword: RewordPrompt? = null,
    val pull: PullPrompt? = null,
    val failure: String? = null,
    val cancelled: Boolean = false,
    val stashPrompt: StashPrompt? = null,
    val stashConflicts: StashConflict? = null,
    val next: RefOperation? = null,
)

/**
 * What an operation gets while it runs besides git: the [cancellation] of a [RefOperation.cancellable] one, where
 * to tell what it does now ([progress], e.g. which remote it fetches; from the thread it runs on), and where to tell
 * that what it does from now on can no longer be cancelled ([uncancellable]: a pull once it has fetched, ADR 0106).
 */
internal class RunContext(
    val cancellation: GitCancellation = GitCancellation(),
    val progress: (String) -> Unit = {},
    val uncancellable: () -> Unit = {},
)

/**
 * A question before an operation may run (ADRs 0041, 0042): a dialog titled [title] asks [question], with [details]
 * below it if any, the [button] that runs the [confirmed] operation (which does not ask again) and Cancel, the default.
 */
internal data class RefConfirmation(
    val title: String,
    val question: String,
    val button: ButtonType,
    val confirmed: RefOperation,
    val details: String? = null,
)

/** An operation of the git log that changes the repository (ADR 0041), run by a [RefWriter]. */
internal sealed interface RefOperation {
    /** What the header says while it runs: "Checking out main…". */
    val progress: String

    /** The title of the error dialog: "Checkout Failed". */
    val errorTitle: String

    /** The question above git's message in the error dialog. */
    val failure: String

    /**
     * What it does with files on disk, as the question about unsaved editors names it before it runs ("Check Out",
     * "Cherry-Pick", "Commit"): it changes them, or takes them as they are on disk (a commit, ADR 0048); `null` if it
     * neither changes nor reads files (or only the index).
     */
    val fileChange: String?

    /**
     * Whether it may be stopped halfway: a fetch, which updates refs one by one and no files (ADR 0043), and a commit,
     * which may wait for slow hooks or a GPG pinentry and is safe to stop, as git removes its lock files on SIGTERM and
     * moves the branch last (ADR 0048). A checkout stopped halfway would leave a half-switched working tree (ADR 0041).
     */
    val cancellable: Boolean get() = false

    /**
     * Whether the window's git status waits for its end (ADR 0062): it changes files or takes them as they are
     * ([fileChange]: a checkout, cherry-pick, revert, their continue, abort and skip, a hard reset, a commit), so that a
     * status read meanwhile would show a state halfway. A fetch, push or a change of refs only does not hold it.
     */
    val holdsStatus: Boolean get() = fileChange != null

    /** Runs it with [writes] in the repository at [root], on the calling thread. */
    fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome

    /** [run], with the [context] of the run: a cancellation and where to tell its progress, if it uses them. */
    fun run(
        writes: GitLogWrites,
        root: Path,
        context: RunContext,
    ): RefOutcome = run(writes, root)
}

/**
 * Checks out the local branch [name]. Unless [confirmed], a detached `HEAD` whose commits it would leave on no branch
 * or tag is not left: the outcome asks first ([leavingQuestion]).
 */
internal data class CheckoutBranch(
    val name: String,
    val confirmed: Boolean = false,
) : RefOperation {
    override val progress get() = "Checking out $name…"
    override val errorTitle get() = CHECKOUT_FAILED
    override val failure get() = "$name could not be checked out."
    override val fileChange get() = CHECK_OUT

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        if (!confirmed) leavingQuestion(writes, root, "$HEADS$name", name, copy(confirmed = true))?.let { return it }
        return writes.switchBranch(root, name).outcome("Checked out $name.")
    }
}

/**
 * Checks out [revision] (a hash or a tag's full refname) detached; [label] names it (short hash, tag name). Unless
 * [confirmed], asks first as [CheckoutBranch] does.
 */
internal data class CheckoutRevision(
    val revision: String,
    val label: String,
    val confirmed: Boolean = false,
) : RefOperation {
    override val progress get() = "Checking out $label…"
    override val errorTitle get() = CHECKOUT_FAILED
    override val failure get() = "$label could not be checked out."
    override val fileChange get() = CHECK_OUT

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        if (!confirmed) leavingQuestion(writes, root, revision, label, copy(confirmed = true))?.let { return it }
        return writes.switchDetached(root, revision).outcome("Checked out $label.")
    }
}

/**
 * Checks out the remote branch [ref] (`refs/remotes/origin/fix`): the local branch of the same name (`fix`) if there
 * is one and it contains the remote branch, else a new local branch tracking it. A local branch that does not contain
 * it is only checked out after asking whether to overwrite it with the remote one. Unless [confirmed], asks first as
 * [CheckoutBranch] does.
 */
internal data class CheckoutRemote(
    val ref: GitRef,
    val confirmed: Boolean = false,
    /** Whether overwriting the local branch with the remote one was confirmed (or is not needed to ask about). */
    val overwrite: Boolean = false,
) : RefOperation {
    /** The local branch's name: the remote branch's without the remote, as git names it. */
    val local: String get() = ref.shortName.substringAfter('/')

    override val progress get() = "Checking out ${ref.shortName}…"
    override val errorTitle get() = CHECKOUT_FAILED
    override val failure get() = "${ref.shortName} could not be checked out."
    override val fileChange get() = CHECK_OUT

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val exists = writes.branchExists(root, local)
        if (exists && !overwrite) overwriteQuestion(writes, root)?.let { return it }
        if (!confirmed) {
            val (target, label) =
                when {
                    !exists -> ref.name to ref.shortName
                    overwrite -> ref.name to local
                    else -> "$HEADS$local" to local
                }
            leavingQuestion(writes, root, target, label, copy(confirmed = true))?.let { return it }
        }
        return when {
            !exists -> {
                writes
                    .switchTracking(
                        root,
                        ref.name,
                    ).outcome("Checked out new branch $local tracking ${ref.shortName}.")
            }

            overwrite -> {
                writes
                    .createBranch(root, local, ref.name, checkout = true, overwrite = true)
                    .outcome("Overwrote $local with ${ref.shortName} and checked it out.")
            }

            else -> {
                writes.switchBranch(root, local).outcome("Checked out local branch $local.")
            }
        }
    }

    /** The outcome that asks whether to overwrite the local branch if it does not contain [ref], else null. */
    private fun overwriteQuestion(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome? {
        val tip =
            when (val found = writes.branchTip(root, local)) {
                GitBranchTip.Missing -> {
                    return null
                }

                GitBranchTip.Unknown -> {
                    return RefOutcome(GitResult(false, "The commit of branch '$local' could not be read."), null)
                }

                is GitBranchTip.At -> {
                    found.commit
                }
            }
        if (writes.isAncestor(root, ref.name, tip) == true) return null
        val question =
            if (writes.isAncestor(root, tip, ref.name) == true) {
                "Local branch '$local' is behind ${ref.shortName}. Overwrite it with ${ref.shortName} and check it out?"
            } else {
                "'$local' has commits that are not on ${ref.shortName}; they will only be reachable through the " +
                    "reflog. Overwrite it with ${ref.shortName} and check it out?"
            }
        val confirmation = RefConfirmation("Overwrite Branch", question, OVERWRITE_BUTTON, copy(overwrite = true))
        return RefOutcome(GitResult(true, ""), null, confirmation = confirmation)
    }
}

/** Creates the branch [name] at [startPoint] (a hash or full refname), checking it out if [checkout]. */
internal data class CreateBranch(
    val name: String,
    val startPoint: String,
    val checkout: Boolean,
    val overwrite: Boolean,
    /** How the start point is shown: a short hash or a ref's short name. */
    val startLabel: String = startPoint,
    /** Whether moving an existing branch away from commits that are not on [startPoint] was confirmed. */
    val confirmed: Boolean = false,
    /** Whether leaving the commits of a detached `HEAD` on no branch or tag was confirmed ([leavingQuestion]). */
    val leavingConfirmed: Boolean = false,
) : RefOperation {
    override val progress get() = "Creating branch $name…"
    override val errorTitle get() = "New Branch Failed"
    override val failure get() = "Branch $name could not be created."
    override val fileChange get() = CHECK_OUT.takeIf { checkout }

    /**
     * Unless [confirmed], an existing branch that [overwrite] would move away from commits not on [startPoint] (they
     * would only be reachable through the reflog) is not moved: the outcome asks first. Then, with [checkout] and unless
     * [leavingConfirmed], a detached `HEAD` whose commits the checkout would leave on no branch or tag is not left.
     */
    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        if (overwrite && !confirmed) {
            when (val tip = writes.branchTip(root, name)) {
                GitBranchTip.Missing -> {}

                GitBranchTip.Unknown -> {
                    return RefOutcome(GitResult(false, "The commit of branch '$name' could not be read."), null)
                }

                is GitBranchTip.At -> {
                    if (writes.isAncestor(root, tip.commit, startPoint) != true) {
                        val question =
                            "'$name' has commits that are not on $startLabel; they will only be reachable through " +
                                "the reflog. Overwrite?"
                        val confirmation =
                            RefConfirmation("Overwrite Branch", question, OVERWRITE_BUTTON, copy(confirmed = true))
                        return RefOutcome(GitResult(true, ""), null, confirmation = confirmation)
                    }
                }
            }
        }
        if (checkout && !leavingConfirmed) {
            // An overwrite asked about keeps its answer; one that was not is checked again when it runs.
            leavingQuestion(writes, root, startPoint, name, copy(leavingConfirmed = true))?.let { return it }
        }
        val done = if (checkout) "Created and checked out branch $name." else "Created branch $name."
        return writes.createBranch(root, name, startPoint, checkout, overwrite).outcome(done)
    }
}

/** Creates the tag [name] at [commit], annotated with [message] unless it is blank. */
internal data class CreateTag(
    val name: String,
    val commit: String,
    val message: String,
) : RefOperation {
    override val progress get() = "Creating tag $name…"
    override val errorTitle get() = "New Tag Failed"
    override val failure get() = "Tag $name could not be created."
    override val fileChange get() = null

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ) = writes.createTag(root, name, commit, message).outcome("Created tag $name.")
}

/** Renames the local branch [old] to [new]. */
internal data class RenameBranch(
    val old: String,
    val new: String,
) : RefOperation {
    override val progress get() = "Renaming branch $old…"
    override val errorTitle get() = "Rename Failed"
    override val failure get() = "Branch $old could not be renamed."
    override val fileChange get() = null

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ) = writes.renameBranch(root, old, new).outcome("Renamed branch $old to $new.")
}

private const val CHECKOUT_FAILED = "Checkout Failed"

/** The action of a checkout, in the question about unsaved editors. */
private const val CHECK_OUT = "Check Out"

private const val HEADS = "refs/heads/"

/**
 * The outcome that asks before a checkout of [target] (a hash or full refname, shown as [label]) leaves commits of a
 * detached `HEAD` on no branch, remote branch or tag, as git would only warn afterwards that it left them behind;
 * [confirmed] runs the checkout without asking again. Asked too if that cannot be read; `null` if none are left.
 */
private fun leavingQuestion(
    writes: GitLogWrites,
    root: Path,
    target: String,
    label: String,
    confirmed: RefOperation,
): RefOutcome? {
    val gone = "will no longer be on any branch or tag"
    val question =
        when (val left = writes.leftBehind(root, target)) {
            0 -> return null
            null -> "Whether commits of the detached HEAD $gone could not be read."
            1 -> "1 commit of the detached HEAD $gone; it stays reachable through the reflog for a while."
            else -> "$left commits of the detached HEAD $gone; they stay reachable through the reflog for a while."
        }
    val confirmation =
        RefConfirmation(
            "Check Out",
            "$question\nTo keep them, cancel and create a branch at HEAD first. Check out $label anyway?",
            CHECKOUT_BUTTON,
            confirmed,
        )
    return RefOutcome(GitResult(true, ""), null, confirmation = confirmation)
}

/**
 * The outcome of an operation that did [done] if git succeeded; if git failed but the change was made all the same (a
 * post-checkout hook failed), that it did, "but git reported an error."
 */
private fun GitResult.outcome(done: String) =
    RefOutcome(
        this,
        when {
            success -> done
            changed -> "${done.removeSuffix(".")}, but git reported an error."
            else -> null
        },
    )

/** What a name in a dialog names: a branch or a tag. */
internal enum class RefNameKind(
    val noun: String,
) {
    BRANCH("branch"),
    TAG("tag"),
}

/**
 * What is wrong with [name] for a new branch or tag ([kind]), or `null` if nothing is: it is empty, would be taken for
 * an option, is not valid for git (`git check-ref-format`), cannot be passed to git in the JVM's locale ([canPass]), is
 * the [current] branch (never overwritten, also with [overwrite]), or is among the [existing] names unless [overwrite].
 * The [unchanged] name (renaming to itself) is an empty problem: the dialog cannot be confirmed, but there is nothing to
 * say.
 */
internal fun nameProblem(
    name: String,
    kind: RefNameKind,
    existing: Set<String>,
    canPass: (String) -> Boolean,
    overwrite: Boolean = false,
    unchanged: String? = null,
    current: String? = null,
): String? {
    val valid =
        when (kind) {
            RefNameKind.BRANCH -> GitRefNames.isValidBranchName(name)
            RefNameKind.TAG -> GitRefNames.isValidTagName(name)
        }
    return when {
        name.isEmpty() -> "Enter a name."
        name == unchanged -> ""
        name.startsWith('-') -> "A name must not start with '-'."
        !valid -> "'$name' is not a valid ${kind.noun} name."
        !canPass(name) -> "This name cannot be passed to git in this locale."
        overwrite && name == current -> "Branch '$name' is checked out."
        name in existing && !overwrite -> "${kind.noun.replaceFirstChar(Char::uppercase)} '$name' already exists"
        else -> null
    }
}

/** What overwriting the existing branch [name] does, told under its name: "Moves 'x' from 1a2b3c4d."; else `null`. */
internal fun overwriteNote(
    name: String,
    overwrite: Boolean,
    tips: Map<String, String>,
): String? = tips[name]?.takeIf { overwrite }?.let { "Moves '$name' from ${it.take(SHORT_HASH)}." }
