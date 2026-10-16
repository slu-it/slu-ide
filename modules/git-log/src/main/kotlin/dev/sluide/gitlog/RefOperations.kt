package dev.sluide.gitlog

import dev.sluide.git.GitBranchTip
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefNames
import dev.sluide.git.GitResult
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * What an operation that changes the repository gave (ADR 0041): git's [result] (a failure is shown in an error
 * dialog), what the header says afterwards ([done], `null` if nothing was done), the branches git refused to delete
 * without force ([refused], with git's message [refusedMessage]), and a question to ask before the operation may run
 * after all ([confirmation]; nothing was done then).
 */
internal data class RefOutcome(
    val result: GitResult,
    val done: String?,
    val refused: List<String> = emptyList(),
    val refusedMessage: String = "",
    val confirmation: String? = null,
)

/** An operation of the git log that changes the repository (ADR 0041), run by a [RefWriter]. */
internal sealed interface RefOperation {
    /** What the header says while it runs: "Checking out main…". */
    val progress: String

    /** The title of the error dialog: "Checkout Failed". */
    val errorTitle: String

    /** The question above git's message in the error dialog. */
    val failure: String

    /** Whether it checks out, which changes files on disk: unsaved editors are asked about first. */
    val checksOut: Boolean

    /** Runs it with [writes] in the repository at [root], on the calling thread. */
    fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome
}

/** Checks out the local branch [name]. */
internal data class CheckoutBranch(
    val name: String,
) : RefOperation {
    override val progress get() = "Checking out $name…"
    override val errorTitle get() = CHECKOUT_FAILED
    override val failure get() = "$name could not be checked out."
    override val checksOut get() = true

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ) = writes.switchBranch(root, name).outcome("Checked out $name.")
}

/** Checks out [revision] (a hash or a tag's full refname) detached; [label] names it (short hash, tag name). */
internal data class CheckoutRevision(
    val revision: String,
    val label: String,
) : RefOperation {
    override val progress get() = "Checking out $label…"
    override val errorTitle get() = CHECKOUT_FAILED
    override val failure get() = "$label could not be checked out."
    override val checksOut get() = true

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ) = writes.switchDetached(root, revision).outcome("Checked out $label.")
}

/**
 * Checks out the remote branch [ref] (`refs/remotes/origin/fix`): the local branch of the same name (`fix`) if there
 * is one, else a new local branch tracking it.
 */
internal data class CheckoutRemote(
    val ref: GitRef,
) : RefOperation {
    /** The local branch's name: the remote branch's without the remote, as git names it. */
    val local: String get() = ref.shortName.substringAfter('/')

    override val progress get() = "Checking out ${ref.shortName}…"
    override val errorTitle get() = CHECKOUT_FAILED
    override val failure get() = "${ref.shortName} could not be checked out."
    override val checksOut get() = true

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ) = if (writes.branchExists(root, local)) {
        writes.switchBranch(root, local).outcome("Checked out local branch $local.")
    } else {
        writes.switchTracking(root, ref.name).outcome("Checked out new branch $local tracking ${ref.shortName}.")
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
) : RefOperation {
    override val progress get() = "Creating branch $name…"
    override val errorTitle get() = "New Branch Failed"
    override val failure get() = "Branch $name could not be created."
    override val checksOut get() = checkout

    /**
     * Unless [confirmed], an existing branch that [overwrite] would move away from commits not on [startPoint] (they
     * would only be reachable through the reflog) is not moved: the outcome asks first.
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
                        return RefOutcome(GitResult(true, ""), null, confirmation = question)
                    }
                }
            }
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
    override val checksOut get() = false

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
    override val checksOut get() = false

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ) = writes.renameBranch(root, old, new).outcome("Renamed branch $old to $new.")
}

/**
 * Deletes the local [branches] and the [tags] (short names); branches that are not fully merged only with [force]. The
 * branches git refuses to delete are handed back, to be deleted with force once confirmed.
 */
internal data class DeleteRefs(
    val branches: List<String>,
    val tags: List<String>,
    val force: Boolean = false,
) : RefOperation {
    override val progress
        get() =
            when {
                branches.size == 1 && tags.isEmpty() -> "Deleting branch ${branches.single()}…"
                tags.size == 1 && branches.isEmpty() -> "Deleting tag ${tags.single()}…"
                else -> "Deleting ${refCount(branches.size, tags.size)}…"
            }
    override val errorTitle get() = "Delete Failed"
    override val failure get() = "Not everything could be deleted."
    override val checksOut get() = false

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val deletion = if (branches.isEmpty()) null else writes.deleteBranches(root, branches, force)
        val tagResult = if (tags.isEmpty()) null else writes.deleteTags(root, tags)
        // A branch git refuses without force is asked about; other failures are shown.
        val refused = if (force) emptyList() else deletion?.notDeleted.orEmpty()
        val branchFailed = deletion != null && !deletion.result.success && refused.isEmpty()
        val failures = listOfNotNull(deletion?.result?.takeIf { branchFailed }, tagResult?.takeIf { !it.success })
        val deletedBranches = if (deletion?.result?.success == true) branches.size else branches.size - refused.size
        val deletedTags = if (tagResult?.success == true) tags.size else 0
        // Whatever git did when it failed is not known: only what surely happened is told.
        val done =
            if (failures.isNotEmpty() || deletedBranches + deletedTags == 0) {
                null
            } else if (deletedBranches + deletedTags == 1) {
                if (deletedBranches == 1) {
                    "Deleted branch ${branches.single { it !in refused }}."
                } else {
                    "Deleted tag ${tags.single()}."
                }
            } else {
                "Deleted ${deletedBranches + deletedTags} refs."
            }
        val result =
            if (failures.isEmpty()) {
                GitResult(true, "")
            } else {
                GitResult(false, failures.joinToString("\n") { it.message })
            }
        return RefOutcome(result, done, refused, deletion?.result?.message.orEmpty())
    }
}

private const val CHECKOUT_FAILED = "Checkout Failed"

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

/** "branch x" / "tag v1" for one, else "2 branches and 1 tag", "3 refs". */
private fun refCount(
    branches: Int,
    tags: Int,
) = when {
    branches + tags > 1 && branches > 0 && tags > 0 -> "${plural(branches, "branch")} and ${plural(tags, "tag")}"
    branches > 1 -> plural(branches, "branch")
    tags > 1 -> plural(tags, "tag")
    else -> if (branches == 1) "branch" else "tag"
}

private fun plural(
    count: Int,
    noun: String,
) = if (count == 1) "1 $noun" else "$count ${if (noun == "branch") "branches" else "${noun}s"}"

/**
 * The question before deleting the local [branches] and [tags] (short names): "Delete branch 'x'?", "Delete tag 'v1'?",
 * "Delete 2 branches?" or "Delete 2 branches and 1 tag?".
 */
internal fun deleteQuestion(
    branches: List<String>,
    tags: List<String>,
): String =
    when {
        branches.size == 1 && tags.isEmpty() -> "Delete branch '${branches.single()}'?"
        tags.size == 1 && branches.isEmpty() -> "Delete tag '${tags.single()}'?"
        else -> "Delete ${refCount(branches.size, tags.size)}?"
    }

/** The question before deleting the [branches] git refused to delete without force. */
internal fun forceDeleteQuestion(branches: List<String>): String =
    if (branches.size == 1) {
        "Force delete branch '${branches.single()}'?"
    } else {
        "Force delete ${branches.size} branches?"
    }

/** What the branches pane can do with its selection (ADR 0041); `null` (or empty) where it cannot. */
internal data class BranchActions(
    /** Checks out the one selected branch or tag; not `HEAD`, the current branch, folders or several. */
    val checkout: RefOperation?,
    /** The one selected ref, to start a new branch from. */
    val newBranchFrom: GitRef?,
    /** The one selected local branch, to rename. */
    val rename: GitRef?,
    /** The selected local branches and tags, if that is all the selection holds and none is current. */
    val delete: List<GitRef>,
)

/**
 * The [BranchActions] for the [selected] nodes of the branches pane; [currentBranch] is the checked-out branch's full
 * refname, whose remote branch of the same name is not checked out either (it would check out the current branch).
 */
internal fun branchActions(
    selected: List<BranchNode>,
    currentBranch: String? = null,
): BranchActions {
    val single = selected.singleOrNull() as? BranchLeaf
    val ref = single?.ref
    val checkout =
        when {
            single == null || single.current -> {
                null
            }

            ref?.kind == GitRefKind.LOCAL_BRANCH -> {
                CheckoutBranch(ref.shortName)
            }

            ref?.kind == GitRefKind.REMOTE_BRANCH -> {
                CheckoutRemote(ref).takeIf {
                    "refs/heads/${it.local}" !=
                        currentBranch
                }
            }

            ref?.kind == GitRefKind.TAG -> {
                CheckoutRevision(ref.name, ref.shortName)
            }

            else -> {
                null
            }
        }
    val deletable =
        selected.isNotEmpty() &&
            selected.all { node ->
                node is BranchLeaf &&
                    !node.current &&
                    (node.ref.kind == GitRefKind.LOCAL_BRANCH || node.ref.kind == GitRefKind.TAG)
            }
    return BranchActions(
        checkout,
        ref,
        ref?.takeIf { it.kind == GitRefKind.LOCAL_BRANCH },
        if (deletable) selected.map { (it as BranchLeaf).ref } else emptyList(),
    )
}

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

/**
 * Runs the [RefOperation]s of a git log one at a time (ADR 0041): on [background] (the thread `sluide-git-write`),
 * the outcome handled through [dispatch]. While one runs, [running] is `true` and no other starts. The outcome of a
 * run started before the last [forget] is dropped.
 */
internal class RefWriter(
    private val writes: GitLogWrites,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
) {
    private val _running = ReadOnlyBooleanWrapper(false)
    val running: ReadOnlyBooleanProperty = _running.readOnlyProperty

    private val generation = AtomicLong()

    /**
     * Runs [operation] in the repository at [root] and hands its outcome to [done], unless one runs already: then
     * returns `false`. A failure of the operation itself (not git's) is an outcome with its message.
     */
    fun run(
        root: Path,
        operation: RefOperation,
        done: (RefOutcome) -> Unit,
    ): Boolean {
        if (_running.get()) return false
        _running.set(true)
        val current = generation.get()
        try {
            background {
                val outcome =
                    try {
                        operation.run(writes, root)
                    } catch (e: RuntimeException) {
                        RefOutcome(GitResult(false, e.message ?: e.toString()), null)
                    }
                dispatch {
                    _running.set(false)
                    if (generation.get() == current) done(outcome)
                }
            }
        } catch (e: RuntimeException) {
            // E.g. the thread was shut down with the window.
            _running.set(false)
            throw e
        }
        return true
    }

    /** Drops the outcome of the run going on, e.g. for another repository. */
    fun forget() {
        generation.incrementAndGet()
    }
}

/** What overwriting the existing branch [name] does, told under its name: "Moves 'x' from 1a2b3c4d."; else `null`. */
internal fun overwriteNote(
    name: String,
    overwrite: Boolean,
    tips: Map<String, String>,
): String? = tips[name]?.takeIf { overwrite }?.let { "Moves '$name' from ${it.take(ChangedFilesPane.SHORT_HASH)}." }
