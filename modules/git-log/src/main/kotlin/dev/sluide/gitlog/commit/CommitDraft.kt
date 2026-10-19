package dev.sluide.gitlog.commit

import dev.sluide.git.GitCommitChanges
import dev.sluide.git.GitInProgress
import dev.sluide.git.GitPendingOperation
import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitPendingRebase
import dev.sluide.gitlog.operations.operationText
import dev.sluide.gitlog.operations.sentence
import dev.sluide.gitlog.ui.SHORT_HASH
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/** What the Commit tab read last (ADR 0048): the [changes] and the commit `HEAD` points at ([head], `null` for none). */
internal data class CommitSnapshot(
    val changes: GitCommitChanges,
    val head: String?,
)

/** Where the reads of the Commit tab are. */
internal enum class CommitLoadState {
    LOADING,
    LOADED,
    FAILED,
}

/**
 * Reads what can be committed in the repository at [root] for the Commit tab (ADR 0048), with [load] on [background]
 * (the tab's own thread, `sluide-git-commit`), the results through [dispatch]. Only while [visible]: a [reload]
 * requested while hidden runs when it is shown again. The newest read wins; while one runs, the [snapshot] read before
 * stays. The message of `HEAD` for Amend is read on the same thread ([headMessage]).
 */
internal class CommitReader(
    val root: Path,
    private val load: (Path) -> CommitSnapshot?,
    private val loadHeadMessage: (Path) -> String?,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
) {
    private val _state = ReadOnlyObjectWrapper(CommitLoadState.LOADING)
    val state: ReadOnlyObjectProperty<CommitLoadState> = _state.readOnlyProperty

    /** What was read last; `null` before the first read succeeded. */
    var snapshot: CommitSnapshot? = null
        private set

    /** Told about every [snapshot] read, also one equal to the one before. */
    var loaded: (CommitSnapshot) -> Unit = {}

    /** Told when a read failed. */
    var failed: () -> Unit = {}

    private val generation = AtomicLong()
    private var stale = true
    private var closed = false

    /** Whether the tab is shown (and the area expanded); reads only then. */
    var visible = false
        set(value) {
            field = value
            if (value && stale) read()
        }

    /** Reads again now if [visible], else when shown next. */
    fun reload() {
        if (closed) return
        if (visible) read() else stale = true
    }

    /** Reads the message of `HEAD` and hands it to [handle] (`null` if there is none or it cannot be read). */
    fun headMessage(handle: (String?) -> Unit) {
        if (closed) return
        background {
            val message = loadHeadMessage(root)
            dispatch { if (!closed) handle(message) }
        }
    }

    /** Drops the reads still going; nothing is read anymore. */
    fun close() {
        closed = true
        generation.incrementAndGet()
    }

    private fun read() {
        if (closed) return
        stale = false
        _state.set(CommitLoadState.LOADING)
        val current = generation.incrementAndGet()
        background {
            if (generation.get() != current) return@background
            val read = load(root)
            dispatch {
                if (generation.get() != current) return@dispatch
                if (read == null) {
                    _state.set(CommitLoadState.FAILED)
                    failed()
                } else {
                    snapshot = read
                    _state.set(CommitLoadState.LOADED)
                    loaded(read)
                }
            }
        }
    }
}

/**
 * Which files of the Commit tab are checked (ADR 0048), by their relative paths, kept across reloads: a tracked file
 * not seen before is checked, an untracked one is not; what the user checked or unchecked stays so while the file is
 * listed, also when it is gone for a while. A path is seen as tracked and as untracked separately, so a file the user
 * stages in a terminal comes back checked as a new tracked one.
 */
internal class CommitChecks {
    private val checked = HashSet<String>()
    private val seenTracked = HashSet<String>()
    private val seenUntracked = HashSet<String>()

    /** Takes the [tracked] and [untracked] paths listed now, checking the new tracked ones. */
    fun update(
        tracked: Collection<String>,
        untracked: Collection<String>,
    ) {
        tracked.filter(seenTracked::add).forEach(checked::add)
        untracked.filter(seenUntracked::add).forEach(checked::remove)
    }

    fun isChecked(path: String) = path in checked

    /** Checks or unchecks all [paths]. */
    fun set(
        paths: Collection<String>,
        check: Boolean,
    ) {
        if (check) checked.addAll(paths) else checked.removeAll(paths.toSet())
    }

    /** Forgets [paths], e.g. once committed: listed again, they count as new. */
    fun forget(paths: Collection<String>) {
        val forgotten = paths.toSet()
        checked.removeAll(forgotten)
        seenTracked.removeAll(forgotten)
        seenUntracked.removeAll(forgotten)
    }

    /** Whether all, some or none of [paths] are checked; none for no paths. */
    fun stateOf(paths: Collection<String>): CheckState {
        val count = paths.count(::isChecked)
        return when {
            count == 0 -> CheckState.NONE
            count == paths.size -> CheckState.ALL
            else -> CheckState.SOME
        }
    }
}

/** How a folder's or group's checkbox shows its files: all checked, some (indeterminate) or none. */
internal enum class CheckState { ALL, SOME, NONE }

/** The line below the Commit button: "2 of 5 files selected", or "Nothing to commit." without changes. */
internal fun commitSummary(
    checked: Int,
    total: Int,
): String =
    when (total) {
        0 -> "Nothing to commit."
        1 -> "$checked of 1 file selected"
        else -> "$checked of $total files selected"
    }

/**
 * Why nothing can be committed while git is in the middle of [inProgress] or paths have [conflicts], in the wording of
 * the backend's refusals (ADR 0042); `null` if neither.
 */
internal fun commitBlocked(
    inProgress: GitInProgress?,
    conflicts: List<String>,
): String? =
    when (inProgress) {
        is GitPendingPick -> {
            val commit = inProgress.commit.take(SHORT_HASH)
            "${inProgress.kind.sentence} of $commit is in progress; continue or abort it in the log first."
        }

        is GitPendingOperation -> {
            operationText(inProgress.operation)
        }

        is GitPendingRebase -> {
            "A rebase is in progress; continue or abort it in the log first."
        }

        null -> {
            when (conflicts.size) {
                0 -> null
                1 -> "1 file has conflicts; resolve it first."
                else -> "${conflicts.size} files have conflicts; resolve them first."
            }
        }
    }

/**
 * Whether Commit can run: a [message] that is not blank, at least one file [checked] or [amend], nothing [blocked] it
 * and no other operation is [running].
 */
internal fun canCommit(
    message: String,
    checked: Int,
    amend: Boolean,
    blocked: Boolean,
    running: Boolean,
): Boolean = message.isNotBlank() && (checked > 0 || amend) && !blocked && !running
