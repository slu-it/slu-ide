package dev.sluide.gitlog.data

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitCommit
import dev.sluide.git.GitComparisonCounts
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogPage
import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitRefs
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** The fakes of the git reads and of the background work that [GitLogPages] runs on, with what they were asked. */
internal abstract class GitLogPagesFixture {
    protected val repo = Path.of("/repo")
    protected val other = Path.of("/other")

    /** The commits of each repository, newest first; missing means git fails. */
    protected val history = mutableMapOf(repo to commits("r", 25), other to commits("o", 3))

    /** The (root, skip, limit) of every load. */
    protected val loads = mutableListOf<Triple<Path, Int, Int>>()

    /** The filter of every load. */
    protected val filters = mutableListOf<GitLogFilter>()

    /** The cancellation of every load, which stops its git. */
    protected val loadCancellations = mutableListOf<GitCancellation>()

    /** Runs while a page loads, e.g. what happens meanwhile on the JavaFX thread. */
    protected var whileLoading: () -> Unit = {}

    /** Which commits pass a filter: by default those whose subject has its text. */
    protected var passes: (GitLogFilter, GitCommit) -> Boolean = { filter, commit -> filter.text in commit.subject }

    /** The commit the text of a filter names as a hash; none by default. */
    protected var hashMatch: (GitLogFilter) -> GitCommit? = { null }

    /** The filter of every question for a hash match. */
    protected val hashLoads = mutableListOf<GitLogFilter>()

    /** The refs of each repository; missing means reading them fails. */
    protected val refs = mutableMapOf<Path, GitRefs>()

    /** The cherry-pick or revert in progress in each repository. */
    protected val pendingPicks = mutableMapOf<Path, GitPendingPick>()

    /** The root of every read of the cherry-pick or revert in progress. */
    protected val pendingLoads = mutableListOf<Path>()

    /** The remotes of each repository; missing means reading them fails. */
    protected val remoteLists = mutableMapOf<Path, List<String>>()

    /** The root of every read of the remotes. */
    protected val remoteLoads = mutableListOf<Path>()

    /** The root of every load of the refs. */
    protected val refLoads = mutableListOf<Path>()

    /** The counts of a comparison by the compared refname; missing means reading them fails. */
    protected val comparisonCounts = mutableMapOf<String, GitComparisonCounts>()

    /** The refname of every read of the counts of a comparison. */
    protected val countLoads = mutableListOf<String>()

    /** The filter of every read of the counts of a comparison. */
    protected val countFilters = mutableListOf<GitLogFilter>()
    protected val background = ArrayDeque<Runnable>()
    protected val dispatched = ArrayDeque<Runnable>()

    /** The log lines of the background tasks of the pages, without their numbers and durations. */
    protected val taskLines = CopyOnWriteArrayList<String>()
    protected val tasks =
        BackgroundTasks.forTests { line ->
            taskLines += line.replace(Regex("task#\\d+"), "task").replace(Regex(" (after|in) \\d+(\\.\\d+)? s"), " $1")
        }

    protected val pages =
        GitLogPages(
            load = { root, filter, skip, limit, cancellation ->
                loads += Triple(root, skip, limit)
                loadCancellations += cancellation
                whileLoading()
                filters += filter
                // As git would answer once stopped.
                if (cancellation.cancelled) return@GitLogPages null
                history[root]?.let { history ->
                    val all = history.filter { passes(filter, it) }
                    val page = all.drop(skip).take(limit)
                    GitLogPage(page, complete = skip + page.size >= all.size)
                }
            },
            loadRefs = { root, _ ->
                refLoads.add(root)
                refs[root]
            },
            loadHashMatch = { _, filter, _ ->
                hashLoads += filter
                hashMatch(filter)
            },
            background = background::addLast,
            dispatch = dispatched::addLast,
            tasks = tasks,
            pageSize = 10,
            prefetch = 3,
            seekLimit = 20,
            loadPending = { root, _ ->
                pendingLoads.add(root)
                pendingPicks[root]
            },
            loadRemotes = { root, _ ->
                remoteLoads.add(root)
                remoteLists[root]
            },
            loadCounts = { _, ref, filter, _ ->
                countLoads += ref
                countFilters += filter
                comparisonCounts[ref]
            },
        )

    /** A first-parent line of [count] commits; every fourth also merges the commit two below it. */
    protected fun commits(
        prefix: String,
        count: Int,
    ) = (1..count).map {
        val parents = listOf(it + 1, if (it % 4 == 0) it + 2 else 0).filter { parent -> parent in 1..count }
        GitCommit(
            "$prefix$it",
            parents.map { parent ->
                "$prefix$parent"
            },
            "A",
            "a@example.com",
            Instant.EPOCH,
            "Commit $it",
        )
    }

    /** Runs the background work, then what it dispatched, as the executor and the JavaFX thread would. */
    protected fun settle() {
        while (background.isNotEmpty() || dispatched.isNotEmpty()) {
            while (background.isNotEmpty()) background.removeFirst().run()
            while (dispatched.isNotEmpty()) dispatched.removeFirst().run()
        }
    }

    protected fun hashes() = pages.rows.map { it.commit.hash }

    /** What [GitLogPages.loadUntil] told: the index found, or why it is missing. */
    protected val seeks = mutableListOf<Any>()

    protected fun loadUntil(hash: String) = pages.loadUntil(hash, { seeks += it }, { seeks += it })
}
