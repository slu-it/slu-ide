package dev.sluide.gitlog.data

import dev.sluide.git.GitCancellation
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
 * the selection has changed meanwhile (latest wins): a newer request (or [close]) stops the git of the older one, which
 * does not start if it is still queued (ADR 0062). The same commit selected again loads nothing, compared by its hash:
 * a reload of the log brings new rows, of which the one of a commit that stays selected only replaces the row in the
 * [state] (its refs may have changed), while its details and changed files, which a commit cannot change, are not read
 * again (ADR 0062); after a failure of either, both are. The links of its message are kept as read: a commit a word
 * names that was fetched since is linked once the commit is selected anew. Used on the JavaFX thread (or whatever
 * [dispatch] stands for in tests).
 */
internal class CommitDetails(
    private val load: (Path, String, GitCancellation) -> GitCommitDetails?,
    private val loadFiles: (Path, GitCommit, GitCancellation) -> List<GitChangedFile>?,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    private val resolve: (Path, List<String>, GitCancellation) -> List<GitRevisionResolution>? = { _, _, _ -> null },
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

    /** Stops the git of the current request. */
    private var cancellation: GitCancellation? = null

    /** The row of the current request, or the newer row of the same commit that replaced it. */
    private var requested: GitLogRow? = null
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
        supersede()
    }

    /** A new request: the results of older ones are dropped, their git is stopped. */
    private fun supersede(): Long {
        cancellation?.cancel()
        cancellation = null
        return generation.incrementAndGet()
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
        val state = _state.get()
        val failed = state is CommitDetailsState.Failed || _files.get() is ChangedFilesState.Failed
        if (!failed && rowOf(state)?.commit?.hash == row.commit.hash) {
            if (rowOf(state) !== row) replaceRow(state, row)
            return
        }
        val root = root() ?: return show(CommitDetailsState.NoneSelected, ChangedFilesState.NoneSelected)
        val current = supersede()
        val token = GitCancellation().also { cancellation = it }
        requested = row
        _state.set(CommitDetailsState.Loading(row))
        _files.set(ChangedFilesState.Loading(row))
        background {
            if (generation.get() != current) return@background
            val details = load(root, row.commit.hash, token)
            val links =
                details?.takeIf { generation.get() == current }?.let { links(root, it.message, token) }.orEmpty()
            dispatch {
                if (generation.get() != current) return@dispatch
                val shown = requested ?: row
                _state.set(
                    details?.let { CommitDetailsState.Loaded(shown, it, links) } ?: CommitDetailsState.Failed(shown),
                )
            }
            // The details show without waiting for the files, which can take longer.
            if (generation.get() != current) return@background
            val files = loadFiles(root, row.commit, token)
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
        token: GitCancellation,
    ): Map<String, String> {
        val candidates =
            hashCandidates(message)
                .map { message.substring(it).lowercase() }
                .distinct()
                .take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return emptyMap()
        val resolutions = resolve(root, candidates, token) ?: return emptyMap()
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
        supersede()
        _state.set(state)
        _files.set(files)
    }

    /** Shows [row] instead of the row of the same commit in [state], which is loading or loaded; nothing is read. */
    private fun replaceRow(
        state: CommitDetailsState,
        row: GitLogRow,
    ) {
        requested = row
        when (state) {
            is CommitDetailsState.Loading -> {
                _state.set(CommitDetailsState.Loading(row))
            }

            is CommitDetailsState.Loaded -> {
                _state.set(state.copy(row = row))
            }

            else -> {}
        }
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

/** As many characters of the hash as the details show. */
internal const val SHORT_HASH = 8
