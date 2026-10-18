package dev.sluide.gitlog.workingtree

import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitWorkingTreeChanges
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * What a working-tree window compares (ADR 0044): the commit [revision] names in the repository at [root] (a full
 * refname, `HEAD` or a full hash, resolved again with every refresh, so that a window of a branch follows it), shown as
 * [label] (`feature`, `HEAD`, a short hash).
 */
internal data class WorkingTreeTarget(
    val root: Path,
    val revision: String,
    val label: String,
)

/** What a working-tree window shows. */
internal sealed interface WorkingTreeState {
    data object Loading : WorkingTreeState

    /** The [files] that differ between [commit] and the working tree. */
    data class Loaded(
        val commit: String,
        val files: List<GitChangedFile>,
    ) : WorkingTreeState

    /** The revision names no commit (anymore). */
    data object Missing : WorkingTreeState

    data object Failed : WorkingTreeState
}

/**
 * Reads what a working-tree window shows (ADR 0044), without JavaFX controls: the files that differ between the
 * [target]'s commit and the working tree ([refresh], with [loadFiles]), and the diff of one of them ([diff], with
 * [loadDiff]), both on [background], their results through [dispatch]. Latest wins for each; [close] drops everything
 * still going. A refresh keeps showing the files read before until the new ones are there.
 */
internal class WorkingTreeComparison(
    val target: WorkingTreeTarget,
    private val loadFiles: (Path, String) -> GitWorkingTreeChanges?,
    private val loadDiff: (Path, String, GitChangedFile) -> GitFileDiff?,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
) {
    private val _state = ReadOnlyObjectWrapper<WorkingTreeState>(WorkingTreeState.Loading)
    val state: ReadOnlyObjectProperty<WorkingTreeState> = _state.readOnlyProperty

    private val refreshes = AtomicLong()
    private val diffs = AtomicLong()
    private var closed = false

    /** Whether a [refresh] is going on. */
    var loading = false
        private set

    /** Reads the files that differ again. */
    fun refresh() {
        if (closed) return
        val current = refreshes.incrementAndGet()
        loading = true
        background {
            if (refreshes.get() != current) return@background
            val changes = loadFiles(target.root, target.revision)
            dispatch {
                if (refreshes.get() != current || closed) return@dispatch
                loading = false
                _state.set(
                    when (changes) {
                        null -> WorkingTreeState.Failed
                        GitWorkingTreeChanges.Missing -> WorkingTreeState.Missing
                        is GitWorkingTreeChanges.Files -> WorkingTreeState.Loaded(changes.commit, changes.files)
                    },
                )
            }
        }
    }

    /**
     * Reads how [file], one of the files shown, differs between their commit and the working tree, and hands the commit
     * and the diff (`null` on failure) to [handle]; nothing while no files are shown.
     */
    fun diff(
        file: GitChangedFile,
        handle: (String, GitFileDiff?) -> Unit,
    ) {
        val commit = (_state.get() as? WorkingTreeState.Loaded)?.commit ?: return
        val current = diffs.incrementAndGet()
        background {
            if (diffs.get() != current) return@background
            val diff = loadDiff(target.root, commit, file)
            dispatch { if (diffs.get() == current && !closed) handle(commit, diff) }
        }
    }

    /** Drops the results of reads still going. */
    fun close() {
        closed = true
        refreshes.incrementAndGet()
        diffs.incrementAndGet()
    }
}
