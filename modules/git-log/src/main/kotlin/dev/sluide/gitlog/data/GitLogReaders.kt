package dev.sluide.gitlog.data

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.git.GitRefs
import java.util.concurrent.CompletableFuture

/**
 * What the git log reads for its view, each on a thread of its own so that none waits behind another: the pages of
 * commits with the refs (ADR 0037) and the selected commit's details, changed files and diffs (ADR 0038) on
 * `sluide-git-log`, the branches containing the shown commit on `sluide-git-branches` (ADR 0045), and the reads of the
 * working-tree windows on `sluide-git-compare` (ADR 0044). Results go through [dispatch], the JavaFX thread in the
 * application.
 */
internal class GitLogReaders(
    source: GitLogSource,
    dispatch: (Runnable) -> Unit,
    tasks: BackgroundTasks,
) : AutoCloseable {
    private val executor = NamedThreads.single("sluide-git-log")

    /** Runs a read on `sluide-git-log`, behind the pages, e.g. what the filters offer. */
    val logReads: (Runnable) -> Unit = executor::execute

    val pages =
        GitLogPages(
            source::page,
            source::refs,
            source::hashMatch,
            executor::execute,
            dispatch,
            tasks,
            loadPending = source::pending,
            loadRemotes = source::remotes,
            loadCounts = source::comparisonCounts,
        )

    val details =
        CommitDetails(
            source::details,
            { root, commit, cancellation ->
                source.changedFiles(root, commit.hash, commit.parents.firstOrNull(), cancellation)
            },
            executor::execute,
            dispatch,
            source::resolveCommits,
        )

    val fileDiffs = FileDiffs(source::fileDiff, executor::execute, dispatch)

    /** Reads the branches containing the shown commit (ADR 0045), not behind the pages of the log nor delaying them. */
    private val branchesExecutor = NamedThreads.single("sluide-git-branches")

    /** Runs a read on `sluide-git-branches`, e.g. a file history's. */
    val branchReads: (Runnable) -> Unit = branchesExecutor::execute

    /** The branches containing the commit whose details are shown (ADR 0045). */
    val commitBranches =
        CommitBranches(source::branchesContaining, branchesExecutor::execute, dispatch).apply {
            currentBranch = { pages.refs?.currentBranch }
        }

    /** The refs the branches containing a commit were read with; new ones read them again. */
    private var branchesRefs: GitRefs? = null

    /** Reads for the working-tree windows (ADR 0044), not behind the pages of the log. */
    private val compareExecutor = NamedThreads.single("sluide-git-compare")

    /** Runs a read on `sluide-git-compare`. */
    val compareReads: (Runnable) -> Unit = compareExecutor::execute

    init {
        details.root = { pages.root }
        // Read once the details are shown, so that they never wait for them; kept while the next commit's load.
        details.state.subscribe { state ->
            when (state) {
                is CommitDetailsState.Loaded -> {
                    commitBranches.show(pages.root, state.row.commit.hash)
                }

                is CommitDetailsState.Loading -> {}

                else -> {
                    commitBranches.show(null, null)
                }
            }
        }
    }

    /** The refs of a loaded first page were read: the branches containing the shown commit are read again if new. */
    fun refsRead() {
        if (pages.refs != branchesRefs) {
            branchesRefs = pages.refs
            commitBranches.refsChanged()
        }
    }

    /** Another repository is opened: nothing of the last one is kept. */
    fun clear() {
        fileDiffs.close()
        commitBranches.clear()
        branchesRefs = null
    }

    /** Completes once everything the threads reading the log and the branches were given so far has run. */
    fun idle(): CompletableFuture<Void> {
        val log = CompletableFuture<Unit>()
        executor.execute { log.complete(Unit) }
        val branches = CompletableFuture<Unit>()
        branchesExecutor.execute { branches.complete(Unit) }
        return CompletableFuture.allOf(log, branches)
    }

    override fun close() {
        compareExecutor.shutdownNow()
        pages.close()
        details.close()
        fileDiffs.close()
        commitBranches.close()
        branchesExecutor.shutdownNow()
        executor.shutdownNow()
    }
}
