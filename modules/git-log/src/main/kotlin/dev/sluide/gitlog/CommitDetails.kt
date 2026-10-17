package dev.sluide.gitlog

import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitDetails
import dev.sluide.git.GitRevisionResolution
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

    /** [links]: the words of the message that name a commit (in lower case) and its full hash (ADR 0045). */
    data class Loaded(
        val row: GitLogRow,
        val details: GitCommitDetails,
        val links: Map<String, String> = emptyMap(),
    ) : CommitDetailsState

    data class Failed(
        val row: GitLogRow,
    ) : CommitDetailsState
}

/**
 * Which commit details the git log shows (ADR 0037), without JavaFX controls: once the [selection] settles on exactly
 * one row, its details are read with [load] and then the files it changed against its first parent with [loadFiles]
 * (ADR 0038) in the repository at [root], in one task on [background]; the words of the message that may be hashes are
 * resolved with [resolve] right after the details, before they are shown (ADR 0045). Each result is handled through
 * [dispatch] as soon as it is read, and dropped if
 * the selection has changed meanwhile (latest wins). The same row selected again loads nothing; a reload of the log
 * brings new rows, so the details and files of a commit that stays selected are read again. Used on the JavaFX thread
 * (or whatever [dispatch] stands for in tests).
 */
internal class CommitDetails(
    private val load: (Path, String) -> GitCommitDetails?,
    private val loadFiles: (Path, GitCommit) -> List<GitChangedFile>?,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    private val resolve: (Path, List<String>) -> List<GitRevisionResolution>? = { _, _ -> null },
) {
    private val _state = ReadOnlyObjectWrapper<CommitDetailsState>(CommitDetailsState.NoneSelected)
    val state: ReadOnlyObjectProperty<CommitDetailsState> = _state.readOnlyProperty

    private val _files = ReadOnlyObjectWrapper<ChangedFilesState>(ChangedFilesState.NoneSelected)

    /** The files the selected commit changed. */
    val files: ReadOnlyObjectProperty<ChangedFilesState> = _files.readOnlyProperty

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
            0 -> show(CommitDetailsState.NoneSelected, ChangedFilesState.NoneSelected)
            1 -> request(rows.single())
            else -> show(CommitDetailsState.SeveralSelected(rows.size), ChangedFilesState.SeveralSelected(rows.size))
        }
    }

    private fun request(row: GitLogRow) {
        if (rowOf(_state.get()) === row) return
        val root = root() ?: return show(CommitDetailsState.NoneSelected, ChangedFilesState.NoneSelected)
        val current = generation.incrementAndGet()
        _state.set(CommitDetailsState.Loading(row))
        _files.set(ChangedFilesState.Loading(row))
        background {
            if (generation.get() != current) return@background
            val details = load(root, row.commit.hash)
            val links = details?.takeIf { generation.get() == current }?.let { links(root, it.message) }.orEmpty()
            dispatch {
                if (generation.get() != current) return@dispatch
                _state.set(details?.let { CommitDetailsState.Loaded(row, it, links) } ?: CommitDetailsState.Failed(row))
            }
            // The details show without waiting for the files, which can take longer.
            if (generation.get() != current) return@background
            val files = loadFiles(root, row.commit)
            dispatch {
                if (generation.get() != current) return@dispatch
                _files.set(files?.let { ChangedFilesState.Loaded(row, it) } ?: ChangedFilesState.Failed(row))
            }
        }
    }

    /**
     * The words of [message] that name exactly one commit, in lower case, with its full hash: all candidates resolved
     * with one call; a branch or tag of that name does not count, nor a failure.
     */
    private fun links(
        root: Path,
        message: String,
    ): Map<String, String> {
        val candidates =
            hashCandidates(message)
                .map { message.substring(it).lowercase() }
                .distinct()
                .take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return emptyMap()
        val resolutions = resolve(root, candidates) ?: return emptyMap()
        return candidates
            .zip(resolutions)
            .mapNotNull { (candidate, resolution) ->
                (resolution as? GitRevisionResolution.Commit)
                    ?.hash
                    ?.takeIf { it.startsWith(candidate) }
                    ?.let { candidate to it }
            }.toMap()
    }

    private fun show(
        state: CommitDetailsState,
        files: ChangedFilesState,
    ) {
        generation.incrementAndGet()
        _state.set(state)
        _files.set(files)
    }

    private fun rowOf(state: CommitDetailsState) =
        when (state) {
            is CommitDetailsState.Loading -> state.row
            is CommitDetailsState.Loaded -> state.row
            is CommitDetailsState.Failed -> state.row
            else -> null
        }
}

/** The most words of a message resolved as hashes, so that git's input stays small. */
private const val MAX_CANDIDATES = 100

/** 7 to 40 hexadecimal characters, not part of a longer word (letters, digits, `_`), e.g. of a longer hex run. */
private val HASH_CANDIDATE = Regex("(?<![\\p{L}\\p{N}_])[0-9a-fA-F]{7,40}(?![\\p{L}\\p{N}_])")

/** Where in [text] the words are that may be (short) commit hashes (ADR 0045). */
internal fun hashCandidates(text: String): List<IntRange> = HASH_CANDIDATE.findAll(text).map { it.range }.toList()
