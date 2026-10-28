package dev.sluide.gitlog.actions

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitPick
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.gitlog.data.GitLogPages
import dev.sluide.gitlog.data.GitLogSource
import dev.sluide.gitlog.data.GitLogWrites
import dev.sluide.gitlog.operations.ABORT_MERGE_BUTTON
import dev.sluide.gitlog.operations.ABORT_REBASE_BUTTON
import dev.sluide.gitlog.operations.AbortMerge
import dev.sluide.gitlog.operations.AbortPick
import dev.sluide.gitlog.operations.AbortRebase
import dev.sluide.gitlog.operations.CheckReword
import dev.sluide.gitlog.operations.CommitChanges
import dev.sluide.gitlog.operations.CreateBranch
import dev.sluide.gitlog.operations.CreateTag
import dev.sluide.gitlog.operations.DELETE_BUTTON
import dev.sluide.gitlog.operations.DeleteRefs
import dev.sluide.gitlog.operations.DeleteRemoteBranches
import dev.sluide.gitlog.operations.FORCE_DELETE_BUTTON
import dev.sluide.gitlog.operations.NewBranchDialog
import dev.sluide.gitlog.operations.NewTagDialog
import dev.sluide.gitlog.operations.PickCommit
import dev.sluide.gitlog.operations.RefConfirmation
import dev.sluide.gitlog.operations.RefNameKind
import dev.sluide.gitlog.operations.RefOperation
import dev.sluide.gitlog.operations.RefOutcome
import dev.sluide.gitlog.operations.RefWriter
import dev.sluide.gitlog.operations.RemoteBranch
import dev.sluide.gitlog.operations.RenameBranch
import dev.sluide.gitlog.operations.RenameBranchDialog
import dev.sluide.gitlog.operations.ResetBranch
import dev.sluide.gitlog.operations.ResetDialog
import dev.sluide.gitlog.operations.RewordCommit
import dev.sluide.gitlog.operations.RewordDialog
import dev.sluide.gitlog.operations.abortButton
import dev.sluide.gitlog.operations.deleteQuestion
import dev.sluide.gitlog.operations.deleteRefsConfirmation
import dev.sluide.gitlog.operations.deleteRemoteQuestion
import dev.sluide.gitlog.operations.forceDeleteConfirmation
import dev.sluide.gitlog.operations.forceDeleteQuestion
import dev.sluide.gitlog.operations.gitErrorDialog
import dev.sluide.gitlog.operations.mergeQuestion
import dev.sluide.gitlog.operations.mergeStoppedDialog
import dev.sluide.gitlog.operations.nameProblem
import dev.sluide.gitlog.operations.overwriteNote
import dev.sluide.gitlog.operations.pickButton
import dev.sluide.gitlog.operations.pickStoppedDialog
import dev.sluide.gitlog.operations.pickTitle
import dev.sluide.gitlog.operations.rebaseStoppedDialog
import dev.sluide.gitlog.operations.refConfirmation
import dev.sluide.gitlog.operations.resetDescription
import dev.sluide.gitlog.operations.resetTitle
import dev.sluide.gitlog.push.PushDialog
import dev.sluide.gitlog.push.PushReader
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.scene.control.ButtonType
import javafx.stage.Window
import javafx.stage.WindowEvent
import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Starts the operations that change the repository of the [pages] (ADR 0041) with the dialogs they ask first and
 * answer with, and opens the Push dialog ([showPush], ADR 0049). [writes] runs them on the thread `sluide-git-write`, one
 * at a time; their outcomes are handled through [dispatch]. What an operation does or did goes to [tellOperation], and
 * starting one clears the header ([clearStatus]). The dialogs are shown on the [window].
 */
internal class RefActions(
    private val pages: GitLogPages,
    private val source: GitLogSource,
    private val writes: GitLogWrites,
    private val dispatch: (Runnable) -> Unit,
    private val showCommitDiff: (commit: String, parent: String?, file: GitChangedFile, tell: (String) -> Unit) -> Unit,
    private val localFile: (GitChangedFile) -> Path?,
    private val window: () -> Window?,
    private val fileExists: (Path) -> Boolean,
    private val clearStatus: () -> Unit,
    private val tellOperation: (String) -> Unit,
    tasks: BackgroundTasks,
) {
    /** Asked before an operation changes files on disk, with its action; `false` cancels it. Set by the view. */
    var beforeFileChange: (action: String) -> Boolean = { true }

    /** Told before an operation runs git, with whether it holds the git status (ADR 0062); set by the view. */
    var onWriteStarted: (holdsStatus: Boolean) -> Unit = {}

    /** Told after every operation, also a failed one, to read the log again (ADR 0041, 0062); set by the view. */
    var onWriteFinished: () -> Unit = {}

    /** Called with a commit that was made (ADR 0048), which ends the Commit tab's draft; set by the view. */
    var committed: (CommitChanges) -> Unit = {}

    /** Opens a file of the working tree; set by the view. */
    var openFile: (Path) -> Unit = {}

    private val writeExecutor =
        NamedThreads.single("sluide-git-write")

    /** Runs the operations that change the repository, one at a time (ADR 0041), each as a task of [tasks]. */
    private val writer =
        RefWriter(writes, writeExecutor::execute, dispatch, tasks, { onWriteStarted(it) }, { onWriteFinished() })

    /** Whether an operation runs, so that no other is offered. */
    val running: ReadOnlyBooleanProperty = writer.running

    /** Whether the operation going on can be cancelled (a fetch, commit or push, ADR 0043). */
    val cancellable: ReadOnlyBooleanProperty = writer.cancellable

    /**
     * Runs a read of the Push dialog on its thread after a delay in milliseconds (ADR 0049); tests drive the delay
     * themselves.
     */
    internal var schedulePush: (ScheduledExecutorService, Long, Runnable) -> Unit =
        { executor, delay, task -> executor.schedule(task, delay, TimeUnit.MILLISECONDS) }

    /** The open Push dialog (ADR 0049); `null` while there is none. */
    internal var pushDialog: PushDialog? = null
        private set

    /**
     * Opens the Push dialog of the repository (ADR 0049), or brings the open one to the front; also while an operation
     * runs, with Push disabled until it ends. Nothing without a repository.
     */
    fun showPush() {
        val root = pages.root ?: return
        pushDialog?.let { return it.show() }
        val pushExecutor =
            NamedThreads.scheduled("sluide-git-push")
        val reader =
            PushReader(
                root,
                source,
                { delay, task -> schedulePush(pushExecutor, delay, task) },
                dispatch,
            ) { name -> pushNameProblem(name) == null }
        val dialog =
            PushDialog(window(), reader, writer.running, ::pushNameProblem).apply {
                push = { operation -> start(operation) }
                showDiff = showCommitDiff
                openFile = { file -> localFile(file)?.let(this@RefActions.openFile) }
                exists = { file -> localFile(file)?.let(fileExists) == true }
            }
        pushDialog = dialog
        dialog.stage.addEventHandler(WindowEvent.WINDOW_HIDDEN) {
            if (pushDialog === dialog) pushDialog = null
            pushExecutor.shutdownNow()
        }
        dialog.show()
    }

    /** What is wrong with [name] as the branch to push to; `null` if nothing. */
    private fun pushNameProblem(name: String) = nameProblem(name, RefNameKind.BRANCH, emptySet(), writes::canPass)

    /**
     * Cherry-picks or reverts ([kind]) [commit]; a merge commit relative to its first parent, once confirmed.
     */
    fun pick(
        commit: GitCommit,
        kind: GitPick,
    ) {
        if (writer.running.get()) return
        if (commit.parents.size <= 1) return start(PickCommit(kind, commit.hash))
        val confirmation =
            RefConfirmation(
                pickTitle(kind),
                mergeQuestion(kind, commit.hash),
                pickButton(kind),
                PickCommit(kind, commit.hash, firstParent = true),
            )
        if (refConfirmation(window(), confirmation).showAndWait().orElse(ButtonType.CANCEL) == confirmation.button) {
            start(confirmation.confirmed)
        }
    }

    /** Asks how to reset the current branch (or a detached `HEAD`) to [commit], then resets it. */
    fun resetTo(commit: GitCommit) {
        if (writer.running.get()) return
        val refs = pages.refs ?: return
        val head = refs.refs.firstOrNull { it.kind == GitRefKind.HEAD }?.commit ?: return
        val branch = refs.currentBranch?.removePrefix("refs/heads/")
        val dialog = ResetDialog(window(), resetTitle(branch), resetDescription(branch, head, commit.hash))
        val mode = dialog.showAndWait().orElse(null) ?: return
        start(ResetBranch(commit.hash, mode, branch))
    }

    /**
     * Reads the message of [commit] and, if it can be changed (ADR 0100), asks for the new one; not on a detached
     * `HEAD`.
     */
    fun editMessage(commit: GitCommit) {
        if (writer.running.get()) return
        val branch = pages.refs?.currentBranch?.removePrefix("refs/heads/")
        if (branch == null) {
            tellOperation("Commit messages can be changed only on a branch.")
            return
        }
        start(CheckReword(branch, commit.hash))
    }

    /**
     * Runs [operation] (ADR 0041) unless one runs already; one that changes files asks [beforeFileChange] first, unless
     * [askFirst] is `false` (it was asked already). The header says what
     * it does, then what it did, and the log is refreshed; a failure is shown in an error dialog with git's message.
     */
    fun start(
        operation: RefOperation,
        askFirst: Boolean = true,
    ) {
        val root = pages.root ?: return
        if (writer.running.get()) return
        val action = operation.fileChange
        if (askFirst && action != null && !beforeFileChange(action)) return
        clearStatus()
        if (writer.run(root, operation, tellOperation) { outcome -> finished(operation, outcome) }) {
            tellOperation(operation.progress)
        }
    }

    private fun finished(
        operation: RefOperation,
        outcome: RefOutcome,
    ) {
        tellOperation(outcome.done.orEmpty())
        outcome.confirmation?.let { confirmation ->
            // Nothing done yet; the unsaved editors were asked about already.
            if (refConfirmation(window(), confirmation).showAndWait().orElse(ButtonType.CANCEL) ==
                confirmation.button
            ) {
                start(confirmation.confirmed, askFirst = false)
            }
            return
        }
        outcome.reword?.let { prompt ->
            val message = RewordDialog(window(), prompt).showAndWait().orElse(null) ?: return
            start(RewordCommit(prompt.branch, prompt.commit, message))
            return
        }
        // A commit that was made (not one cancelled) ends the draft (ADR 0048); a failed one keeps it.
        // Commit and Push opens the Push dialog after a commit that was made (ADR 0049).
        if (operation is CommitChanges && outcome.result.success && outcome.result.changed) {
            committed(operation)
            if (operation.push) showPush()
        }
        if (!outcome.result.success) {
            // Done all the same (a failing post-checkout hook): the header says what was done.
            val header = errorHeader(operation, outcome)
            gitErrorDialog(window(), operation.errorTitle, header, outcome.result.message).showAndWait()
        }
        outcome.stopped?.let { stop ->
            // Resolve (or closing the dialog) leaves it for the user, with the bar above the table.
            if (pickStoppedDialog(window(), stop).showAndWait().orElse(null) == abortButton(stop.kind)) {
                start(AbortPick(stop.kind, stop.commit))
            }
        }
        outcome.rebaseStopped?.let { stop ->
            if (rebaseStoppedDialog(window(), stop).showAndWait().orElse(null) == ABORT_REBASE_BUTTON) {
                start(AbortRebase)
            }
        }
        outcome.mergeStopped?.let { stop ->
            if (mergeStoppedDialog(window(), stop).showAndWait().orElse(null) == ABORT_MERGE_BUTTON) {
                start(AbortMerge)
            }
        }
        if (outcome.refused.isNotEmpty()) {
            val confirmation =
                forceDeleteConfirmation(window(), forceDeleteQuestion(outcome.refused), outcome.refusedMessage)
            if (confirmation.showAndWait().orElse(ButtonType.CANCEL) == FORCE_DELETE_BUTTON) {
                start(DeleteRefs(outcome.refused, emptyList(), force = true))
            }
        }
    }

    /** The short names of the refs of [kind] the log read last. */
    private fun refNames(kind: GitRefKind): Set<String> =
        pages.refs
            ?.refs
            .orEmpty()
            .filter { it.kind == kind }
            .mapTo(HashSet()) { it.shortName }

    /**
     * Asks for a new branch at [startPoint] (a hash or full refname, shown as [startLabel]) and creates it; the current
     * branch is never overwritten, another one only once confirmed if it has commits that are not on the start point.
     */
    fun newBranch(
        startPoint: String,
        startLabel: String,
    ) {
        val existing = refNames(GitRefKind.LOCAL_BRANCH)
        val current = pages.refs?.currentBranch?.removePrefix("refs/heads/")
        val tips =
            pages.refs
                ?.refs
                .orEmpty()
                .filter { it.kind == GitRefKind.LOCAL_BRANCH }
                .associate { it.shortName to it.commit }
        val dialog =
            NewBranchDialog(
                window(),
                { name, overwrite ->
                    nameProblem(name, RefNameKind.BRANCH, existing, writes::canPass, overwrite, current = current)
                },
                { name, overwrite -> overwriteNote(name, overwrite, tips) },
            )
        val branch = dialog.showAndWait().orElse(null) ?: return
        start(CreateBranch(branch.name, startPoint, branch.checkout, branch.overwrite, startLabel))
    }

    /** Asks for a new tag at [commit] and creates it. */
    fun newTag(commit: String) {
        val existing = refNames(GitRefKind.TAG)
        val dialog = NewTagDialog(window()) { name -> nameProblem(name, RefNameKind.TAG, existing, writes::canPass) }
        val tag = dialog.showAndWait().orElse(null) ?: return
        start(CreateTag(tag.name, commit, tag.message))
    }

    /** Asks for a new name of the local branch [ref] and renames it. */
    fun renameBranch(ref: GitRef) {
        val old = ref.shortName
        val existing = refNames(GitRefKind.LOCAL_BRANCH)
        val dialog =
            RenameBranchDialog(window(), old) { name ->
                nameProblem(name, RefNameKind.BRANCH, existing, writes::canPass, unchanged = old)
            }
        val new = dialog.showAndWait().orElse(null) ?: return
        start(RenameBranch(old, new))
    }

    /**
     * Asks whether to delete the local branches and tags [refs] and deletes them; with tags, also on the remotes the
     * user checks (ADR 0078), from those the log read last.
     */
    fun deleteRefs(refs: List<GitRef>) {
        if (writer.running.get()) return
        val branches = refs.filter { it.kind == GitRefKind.LOCAL_BRANCH }.map { it.shortName }
        val tags = refs.filter { it.kind == GitRefKind.TAG }.map { it.shortName }
        val names = branches.map { "branch $it" } + tags.map { "tag $it" }
        val remotes = if (tags.isEmpty()) emptyList() else pages.remotes.orEmpty()
        val confirmation = deleteRefsConfirmation(window(), deleteQuestion(branches, tags), names, remotes, tags.size)
        if (confirmation.alert.showAndWait().orElse(ButtonType.CANCEL) != DELETE_BUTTON) return
        start(DeleteRefs(branches, tags, remotes = confirmation.checkedRemotes))
    }

    /** Asks whether to delete the remote [branches] on their remotes (ADR 0078) and deletes them. */
    fun deleteRemoteBranches(branches: List<RemoteBranch>) {
        if (writer.running.get() || branches.isEmpty()) return
        val names = branches.map { "remote branch ${it.ref.shortName}" }
        val confirmation = deleteRefsConfirmation(window(), deleteRemoteQuestion(branches), names)
        if (confirmation.alert.showAndWait().orElse(ButtonType.CANCEL) != DELETE_BUTTON) return
        start(DeleteRemoteBranches(branches))
    }

    /** Cancels the fetch, commit or push going on, if any (ADR 0043). */
    fun cancel() {
        writer.cancel()
    }

    /**
     * Another repository: the Push dialog closes, and an operation going on ends unnoticed; a fetch is stopped
     * (ADR 0043).
     */
    fun reset() {
        pushDialog?.close()
        writer.cancel()
        writer.forget()
        tellOperation("")
    }

    /** Closes the Push dialog and drops the outcome of the operation going on; the window closes. */
    fun close() {
        pushDialog?.close()
        // A fetch is stopped: harmless, it updates refs one by one and no files (ADR 0043).
        writer.cancel()
        writer.forget()
        // Any other running write is not interrupted: stopped halfway, a checkout would leave a half-switched working
        // tree (ADR 0041). Git finishes on its own; its outcome is dropped.
        writeExecutor.shutdown()
    }
}

/**
 * The header of the error dialog of a failed [operation]: what it did all the same (a failing post-checkout hook),
 * else the question its outcome brings, else the operation's.
 */
internal fun errorHeader(
    operation: RefOperation,
    outcome: RefOutcome,
): String = outcome.done?.takeIf { outcome.result.changed } ?: outcome.failure ?: operation.failure
