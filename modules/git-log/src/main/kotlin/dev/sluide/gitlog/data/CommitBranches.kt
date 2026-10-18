package dev.sluide.gitlog.data

import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/** What the details say about the branches that contain the shown commit (ADR 0045). */
internal sealed interface BranchesState {
    /** No commit shown. */
    data object None : BranchesState

    /** The branches of [hash] are being read. */
    data class Reading(
        val hash: String,
    ) : BranchesState

    /** The branches containing [hash], as [orderedBranches] orders them. */
    data class Read(
        val hash: String,
        val names: List<String>,
    ) : BranchesState

    data class Failed(
        val hash: String,
    ) : BranchesState
}

/**
 * The branches containing the commit whose details are shown (ADR 0045), without JavaFX controls: [show] reads them
 * with [load] on [background], separately from the details, and hands the result to [state] through [dispatch]; a
 * request superseded before it starts reads nothing, a result for a commit that is no longer shown is dropped (but kept
 * for later). Results are kept per commit until the
 * refs change ([refsChanged], which reads the shown commit's again) or another folder opens ([clear]). The names are
 * ordered with the current branch from [currentBranch]. Used on the JavaFX thread (or whatever [dispatch] stands for in
 * tests).
 */
internal class CommitBranches(
    private val load: (Path, String) -> List<String>?,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
) {
    private val _state = ReadOnlyObjectWrapper<BranchesState>(BranchesState.None)
    val state: ReadOnlyObjectProperty<BranchesState> = _state.readOnlyProperty

    /** The full refname of the checked-out branch, `null` when detached or not known. */
    var currentBranch: () -> String? = { null }

    /** The refnames read, by commit. */
    private val cache = HashMap<String, List<String>>()

    /** The repository and commit shown, `null` for none. */
    private var shown: Pair<Path, String>? = null

    /** The current request; a result of an earlier one is not shown. */
    private val generation = AtomicLong()

    /** Counts the clearings of the cache; a result read before one is not kept. */
    private var epoch = 0L

    /** Shows the branches containing the commit [hash] of the repository at [root], reading them if needed; `null` none. */
    fun show(
        root: Path?,
        hash: String?,
    ) {
        if (root == null || hash == null) {
            shown = null
            generation.incrementAndGet()
            _state.set(BranchesState.None)
            return
        }
        val state = _state.get()
        // Already shown or being read; a failure is tried again.
        if (shown == root to hash && (state is BranchesState.Reading || state is BranchesState.Read)) return
        shown = root to hash
        val cached = cache[hash]
        if (cached != null) {
            generation.incrementAndGet()
            _state.set(read(hash, cached))
            return
        }
        _state.set(BranchesState.Reading(hash))
        request(root, hash)
    }

    /** The refs changed: forgets what was read and reads the shown commit's branches again, showing the old ones meanwhile. */
    fun refsChanged() {
        epoch++
        cache.clear()
        val (root, hash) = shown ?: return
        request(root, hash)
    }

    /** Forgets everything, e.g. for another folder. */
    fun clear() {
        epoch++
        cache.clear()
        show(null, null)
    }

    /** Drops the results of requests still going. */
    fun close() {
        shown = null
        generation.incrementAndGet()
    }

    private fun request(
        root: Path,
        hash: String,
    ) {
        val current = generation.incrementAndGet()
        val readIn = epoch
        background {
            // Superseded before it started (e.g. stepping through the rows): nothing to read.
            if (generation.get() != current) return@background
            val names = load(root, hash)
            dispatch {
                if (names != null && epoch == readIn) cache[hash] = names
                if (generation.get() != current) return@dispatch
                _state.set(names?.let { read(hash, it) } ?: BranchesState.Failed(hash))
            }
        }
    }

    private fun read(
        hash: String,
        refnames: List<String>,
    ) = BranchesState.Read(hash, orderedBranches(refnames, currentBranch()))
}

/** How many branch names the details show before "Show all". */
internal const val SHOWN_BRANCHES = 6

/**
 * The short names (`main`, `origin/main`) of the branches [refnames] (full refnames) as the details list them: the
 * [currentBranch] first, then the local branches, then the remote branches, each by name (ignoring case first);
 * symbolic remote `HEAD`s (`refs/remotes/origin/HEAD`) and anything else are left out.
 */
internal fun orderedBranches(
    refnames: List<String>,
    currentBranch: String?,
): List<String> {
    val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { it: String -> it }.thenBy { it }
    val local = refnames.filter { it.startsWith(LOCAL_PREFIX) }.distinct()
    val remote =
        refnames
            .filter { it.startsWith(REMOTE_PREFIX) && !it.endsWith("/HEAD") }
            .distinct()
    val current = local.filter { it == currentBranch }
    return current.map { it.removePrefix(LOCAL_PREFIX) } +
        (local - current.toSet()).map { it.removePrefix(LOCAL_PREFIX) }.sortedWith(byName) +
        remote.map { it.removePrefix(REMOTE_PREFIX) }.sortedWith(byName)
}

/**
 * The line of the details about the branches [names] (ADR 0045), as in IntelliJ: `In no branch`, `In 1 branch: main`,
 * `In 3 branches: main, origin/main, feature/x`; with more than [SHOWN_BRANCHES], unless [all], only the first of them
 * followed by `, …`, and [showAll] is the text of the link that shows the rest (`Show all (9)`), else `null`.
 */
internal data class BranchesLine(
    val text: String,
    val showAll: String?,
)

internal fun branchesLine(
    names: List<String>,
    all: Boolean,
): BranchesLine {
    val count = names.size
    if (count == 0) return BranchesLine("In no branch", null)
    val prefix = if (count == 1) "In 1 branch: " else "In $count branches: "
    if (all || count <= SHOWN_BRANCHES) return BranchesLine(prefix + names.joinToString(", "), null)
    return BranchesLine(prefix + names.take(SHOWN_BRANCHES).joinToString(", ") + ", …", "Show all ($count)")
}

private const val LOCAL_PREFIX = "refs/heads/"
private const val REMOTE_PREFIX = "refs/remotes/"
