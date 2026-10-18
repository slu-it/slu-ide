package dev.sluide.gitlog.push

import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitOutgoing
import dev.sluide.gitlog.data.GitLogSource
import dev.sluide.gitlog.operations.Fetch
import dev.sluide.gitlog.operations.isProtectedBranch
import dev.sluide.gitlog.operations.nameProblem
import dev.sluide.gitlog.ui.SHORT_HASH
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * What the Push dialog shows (ADR 0049), read in one go: the checked-out [branch] (short name, `null` when `HEAD` is
 * detached), the [head] commit (`null` before the first commit), the names of the [remotes], whether the branch
 * [hasUpstream], the [remote] and [target] branch the push goes to, what it would send ([outgoing], `null` if not read:
 * nothing to push from, or a target name git cannot take) and the full name of the target's remote-tracking ref
 * ([trackingRef]). [failed]: the repository could not be read.
 */
internal data class PushState(
    val branch: String?,
    val head: String?,
    val remotes: List<String>,
    val hasUpstream: Boolean,
    val remote: String?,
    val target: String,
    val outgoing: GitOutgoing?,
    val trackingRef: String?,
    val failed: Boolean = false,
) {
    /** Why nothing can be pushed at all: `HEAD` detached, no commit yet, no remote; `null` otherwise. */
    val blocked: String?
        get() =
            when {
                failed -> null
                branch == null -> DETACHED
                head == null -> UNBORN
                remotes.isEmpty() -> NO_REMOTE
                else -> null
            }

    /** Whether the target has `HEAD` already: no commits to send, and its remote-tracking ref is at `HEAD`. */
    val upToDate: Boolean
        get() = outgoing != null && outgoing.commits.isEmpty() && outgoing.remoteCommit == head

    /** Whether the remote has no branch [target] as of the last fetch: the push creates it. */
    val isNew: Boolean get() = outgoing != null && outgoing.remoteCommit == null

    /**
     * Whether a normal push has nothing to send: the target exists and has every commit of `HEAD` ([upToDate], or
     * `HEAD` is behind it). A force push may still move the target back to `HEAD`.
     */
    val nothingToPush: Boolean
        get() = outgoing != null && outgoing.commits.isEmpty() && outgoing.remoteCommit != null

    /** Why a normal push has nothing to send: up to date, or `HEAD` behind the target; `null` otherwise. */
    val nothingToPushNote: String?
        get() =
            when {
                upToDate -> UP_TO_DATE
                nothingToPush -> "Your branch is behind $remote/$target; nothing to push."
                else -> null
            }

    companion object {
        const val DETACHED = "HEAD is detached: check out a branch to push it."
        const val UNBORN = "There is no commit to push."
        const val NO_REMOTE = "The repository has no remote. Add one in a terminal: git remote add <name> <url>."
        const val UP_TO_DATE = "Everything is up to date."

        /** At most this many outgoing commits are listed. */
        const val LIMIT = 1000

        val FAILED = PushState(null, null, emptyList(), false, null, "", null, null, failed = true)
    }
}

/** A row of the Push dialog's commits: all of them, one commit, or that there are more than listed. */
internal sealed interface PushRow {
    /** All outgoing commits: [count] of them, and [more] that are not listed. */
    data class All(
        val count: Int,
        val more: Boolean,
    ) : PushRow

    data class Commit(
        val commit: GitCommit,
    ) : PushRow

    data object More : PushRow
}

/** The rows of the commits of [outgoing]: "All", each commit, "and more…" if there are more; none without commits. */
internal fun pushRows(outgoing: GitOutgoing?): List<PushRow> {
    if (outgoing == null || outgoing.commits.isEmpty()) return emptyList()
    return listOf(PushRow.All(outgoing.commits.size, outgoing.more)) +
        outgoing.commits.map(PushRow::Commit) +
        listOfNotNull(PushRow.More.takeIf { outgoing.more })
}

/**
 * Which commit, against which parent, the files of [row] are (ADR 0049): for a commit its own against its first
 * parent; for All (and the row of more commits) `HEAD`, the newest outgoing commit, against the [GitOutgoing.base];
 * `null` without commits.
 */
internal fun pushFilesOf(
    row: PushRow?,
    outgoing: GitOutgoing?,
): Pair<String, String?>? {
    if (row is PushRow.Commit) return row.commit.hash to row.commit.parents.firstOrNull()
    val head = outgoing?.commits?.firstOrNull() ?: return null
    return head.hash to outgoing.base
}

/** What the files of the Push dialog's selected row are. */
internal sealed interface PushFiles {
    data object None : PushFiles

    /** The files of [commit] against [parent] are read. */
    data class Loading(
        val commit: String,
        val parent: String?,
    ) : PushFiles

    data class Loaded(
        val commit: String,
        val parent: String?,
        val files: List<GitChangedFile>,
    ) : PushFiles

    data object Failed : PushFiles
}

/** What the push dialog says when the commits are not listed: still loading, a failure, why it cannot push, none. */
internal fun pushPlaceholder(state: PushState?): String =
    when {
        state == null -> {
            "Loading…"
        }

        state.failed -> {
            "The commits could not be read."
        }

        state.blocked != null -> {
            state.blocked.orEmpty()
        }

        state.outgoing == null -> {
            ""
        }

        state.isNew && state.head != null -> {
            val head = state.head.take(SHORT_HASH)
            "No new commits: pushing creates ${state.remote}/${state.target} at $head."
        }

        else -> {
            "Nothing to push."
        }
    }

/**
 * The note that a normal push will be rejected because the target has [behind] commits that `HEAD` has not; `null`
 * for none.
 */
internal fun behindNote(
    remote: String,
    target: String,
    behind: Int,
): String? {
    if (behind <= 0) return null
    val commits = if (behind == 1) "1 commit" else "$behind commits"
    return "$remote/$target has $commits that your branch does not have; a normal push will be rejected. " +
        "Fetch, then merge or rebase, or force push."
}

/**
 * What a force push of [branch] to [target] of [remote] asks first: the question and what it does, [behind] commits
 * lost, else that the target will be set to [head].
 */
internal fun forcePushQuestion(
    branch: String,
    remote: String,
    target: String,
    behind: Int,
    head: String,
): Pair<String, String> {
    val where = "$remote/$target"
    val details =
        if (behind > 0) {
            val commits = if (behind == 1) "1 commit on $where that is" else "$behind commits on $where that are"
            "$commits not in your branch will be lost."
        } else {
            "$where will be set to ${head.take(SHORT_HASH)}."
        }
    return "Force push $branch to $where?" to details
}

/**
 * Whether the Push dialog can push with what it read last ([state]): there is something to send (not detached,
 * unborn, without a remote or failed, and not [PushState.nothingToPush]), the target name has no [nameProblem],
 * nothing is [loading] and no other operation is [running].
 */
internal fun canPush(
    state: PushState?,
    nameProblem: String?,
    loading: Boolean,
    running: Boolean,
): Boolean = state != null && pushable(state, nameProblem, loading, running) && !state.nothingToPush

/**
 * Whether the Push dialog can force push with what it read last ([state]): as for [canPush], also when `HEAD` is
 * behind the target (a force push moves it back), but not when it is up to date, nor to a protected branch.
 */
internal fun canForcePush(
    state: PushState?,
    nameProblem: String?,
    loading: Boolean,
    running: Boolean,
): Boolean =
    state != null &&
        pushable(state, nameProblem, loading, running) &&
        !state.upToDate &&
        !isProtectedBranch(state.target)

private fun pushable(
    state: PushState,
    nameProblem: String?,
    loading: Boolean,
    running: Boolean,
) = !state.failed && state.blocked == null && state.outgoing != null && nameProblem == null && !loading && !running

/**
 * Reads what the Push dialog of the repository at [root] shows (ADR 0049) with [source], without JavaFX controls: the
 * branch, remotes and what a push sends ([load], into [state]) and the files of a row ([files], into [files]), each on
 * [background] after the given delay in milliseconds, the results through [dispatch]; latest wins for each. A target
 * name that is not [valid] is not read. [close] drops everything still going.
 */
internal class PushReader(
    private val root: Path,
    private val source: GitLogSource,
    private val background: (Long, Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    private val valid: (String) -> Boolean,
) {
    private val _state = ReadOnlyObjectWrapper<PushState?>(null)

    /** What was read last; `null` before the first read. */
    val state: ReadOnlyObjectProperty<PushState?> = _state.readOnlyProperty

    private val _loading = ReadOnlyBooleanWrapper(false)

    /** Whether a [load] is going on (or waits for its delay). */
    val loading: ReadOnlyBooleanProperty = _loading.readOnlyProperty

    private val _files = ReadOnlyObjectWrapper<PushFiles>(PushFiles.None)

    /** The files of the row asked for last. */
    val files: ReadOnlyObjectProperty<PushFiles> = _files.readOnlyProperty

    private val loads = AtomicLong()
    private val fileReads = AtomicLong()
    private var closed = false

    /**
     * Reads everything for a push to the [target] branch of [remote] after [delay] ms; the defaults ([GitLogSource
     * .pushTarget]) if either is `null`, or if the branch is not the one read before (checked out meanwhile).
     */
    fun load(
        remote: String?,
        target: String?,
        delay: Long = 0,
    ) {
        if (closed) return
        val current = loads.incrementAndGet()
        _loading.set(true)
        val before = _state.get()
        val keep = if (before != null && !before.failed && remote != null && target != null) before.branch else null
        background(delay) {
            if (loads.get() != current) return@background
            val state = read(remote, target, keep)
            dispatch {
                if (loads.get() != current || closed) return@dispatch
                _loading.set(false)
                _state.set(state)
            }
        }
    }

    /** Reads the files [commit] changed against [parent]. */
    fun files(
        commit: String,
        parent: String?,
    ) {
        if (closed) return
        val current = fileReads.incrementAndGet()
        _files.set(PushFiles.Loading(commit, parent))
        background(0) {
            if (fileReads.get() != current) return@background
            val files = source.changedFiles(root, commit, parent)
            dispatch {
                if (fileReads.get() != current || closed) return@dispatch
                _files.set(if (files == null) PushFiles.Failed else PushFiles.Loaded(commit, parent, files))
            }
        }
    }

    /** Shows no files. */
    fun noFiles() {
        fileReads.incrementAndGet()
        _files.set(PushFiles.None)
    }

    /** Drops the reads still going. */
    fun close() {
        closed = true
        loads.incrementAndGet()
        fileReads.incrementAndGet()
    }

    /** The asked [remote] and [target] count only for the branch [keep] they were chosen for. */
    private fun read(
        remote: String?,
        target: String?,
        keep: String?,
    ): PushState {
        val refs = source.refs(root) ?: return PushState.FAILED
        val branch = refs.currentBranch?.removePrefix(HEADS)
        val head = source.headCommit(root)
        val remotes = source.remotes(root) ?: return PushState.FAILED
        if (branch == null || head == null || remotes.isEmpty()) {
            return PushState(branch, head, remotes, false, remotes.firstOrNull(), branch.orEmpty(), null, null)
        }
        val default = source.pushTarget(root, branch) ?: return PushState.FAILED
        val chosen =
            if (keep == branch && remote != null && target != null && remote in remotes) remote to target else null
        val (toRemote, toBranch) = chosen ?: (default.remote to default.branch)
        val state = PushState(branch, head, remotes, default.hasUpstream, toRemote, toBranch, null, null)
        if (!valid(toBranch)) return state
        val outgoing = source.outgoing(root, toRemote, toBranch, PushState.LIMIT) ?: return PushState.FAILED
        return state.copy(outgoing = outgoing, trackingRef = source.trackingRef(root, toRemote, toBranch))
    }

    private companion object {
        const val HEADS = "refs/heads/"
    }
}
