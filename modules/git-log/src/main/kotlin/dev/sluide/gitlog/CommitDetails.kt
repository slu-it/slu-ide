package dev.sluide.gitlog

import dev.sluide.git.GitCommitDetails
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/** What the details pane shows (ADR 0037). */
internal sealed interface CommitDetailsState {
    data object NoneSelected : CommitDetailsState

    data class SeveralSelected(
        val count: Int,
    ) : CommitDetailsState

    /** The details of [row] are being read. */
    data class Loading(
        val row: GitLogRow,
    ) : CommitDetailsState

    data class Loaded(
        val row: GitLogRow,
        val details: GitCommitDetails,
    ) : CommitDetailsState

    data class Failed(
        val row: GitLogRow,
    ) : CommitDetailsState
}

/**
 * Which commit details the git log shows (ADR 0037), without JavaFX controls: once the [selection] settles on exactly
 * one row, its details are read with [load] in the repository at [root], on [background]; the result is handled
 * through [dispatch], and dropped if the selection has changed meanwhile (latest wins). The same row selected again
 * loads nothing; a reload of the log brings new rows, so the details of a commit that stays selected are read again.
 * Used on the JavaFX thread (or whatever [dispatch] stands for in tests).
 */
internal class CommitDetails(
    private val load: (Path, String) -> GitCommitDetails?,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
) {
    private val _state = ReadOnlyObjectWrapper<CommitDetailsState>(CommitDetailsState.NoneSelected)
    val state: ReadOnlyObjectProperty<CommitDetailsState> = _state.readOnlyProperty

    /** The selected rows. */
    var selection: () -> List<GitLogRow> = { emptyList() }

    /** The repository the rows are from. */
    var root: () -> Path? = { null }

    /** The current request; read on the background thread to skip requests that are no longer wanted. */
    private val generation = AtomicLong()
    private var settling = false
    private var closed = false

    /**
     * Notes a change of the selection; it is looked at once the changes of the current event are done (a reload
     * clears and restores it), through [dispatch].
     */
    fun selectionChanged() {
        if (settling || closed) return
        settling = true
        dispatch {
            settling = false
            if (!closed) settle()
        }
    }

    /** Drops the results of requests still going. */
    fun close() {
        closed = true
        generation.incrementAndGet()
    }

    private fun settle() {
        val rows = selection()
        when (rows.size) {
            0 -> show(CommitDetailsState.NoneSelected)
            1 -> request(rows.single())
            else -> show(CommitDetailsState.SeveralSelected(rows.size))
        }
    }

    private fun request(row: GitLogRow) {
        if (rowOf(_state.get()) === row) return
        val root = root() ?: return show(CommitDetailsState.NoneSelected)
        val current = generation.incrementAndGet()
        _state.set(CommitDetailsState.Loading(row))
        background {
            if (generation.get() != current) return@background
            val details = load(root, row.commit.hash)
            dispatch {
                if (generation.get() != current) return@dispatch
                _state.set(details?.let { CommitDetailsState.Loaded(row, it) } ?: CommitDetailsState.Failed(row))
            }
        }
    }

    private fun show(state: CommitDetailsState) {
        generation.incrementAndGet()
        _state.set(state)
    }

    private fun rowOf(state: CommitDetailsState) =
        when (state) {
            is CommitDetailsState.Loading -> state.row
            is CommitDetailsState.Loaded -> state.row
            is CommitDetailsState.Failed -> state.row
            else -> null
        }
}
