package dev.sluide.gitlog

import dev.sluide.git.GitCommit
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogPage
import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitPick
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitLogPagesTest {
    private val repo = Path.of("/repo")
    private val other = Path.of("/other")

    /** The commits of each repository, newest first; missing means git fails. */
    private val history = mutableMapOf(repo to commits("r", 25), other to commits("o", 3))

    /** The (root, skip, limit) of every load. */
    private val loads = mutableListOf<Triple<Path, Int, Int>>()

    /** The filter of every load. */
    private val filters = mutableListOf<GitLogFilter>()

    /** Which commits pass a filter: by default those whose subject has its text. */
    private var passes: (GitLogFilter, GitCommit) -> Boolean = { filter, commit -> filter.text in commit.subject }

    /** The commit the text of a filter names as a hash; none by default. */
    private var hashMatch: (GitLogFilter) -> GitCommit? = { null }

    /** The filter of every question for a hash match. */
    private val hashLoads = mutableListOf<GitLogFilter>()

    /** The refs of each repository; missing means reading them fails. */
    private val refs = mutableMapOf<Path, GitRefs>()

    /** The cherry-pick or revert in progress in each repository. */
    private val pendingPicks = mutableMapOf<Path, GitPendingPick>()

    /** The root of every read of the cherry-pick or revert in progress. */
    private val pendingLoads = mutableListOf<Path>()

    /** The root of every load of the refs. */
    private val refLoads = mutableListOf<Path>()
    private val background = ArrayDeque<Runnable>()
    private val dispatched = ArrayDeque<Runnable>()

    private val pages =
        GitLogPages(
            load = { root, filter, skip, limit ->
                loads += Triple(root, skip, limit)
                filters += filter
                history[root]?.let { history ->
                    val all = history.filter { passes(filter, it) }
                    val page = all.drop(skip).take(limit)
                    GitLogPage(page, complete = skip + page.size >= all.size)
                }
            },
            loadRefs = { root ->
                refLoads.add(root)
                refs[root]
            },
            loadHashMatch = { _, filter ->
                hashLoads += filter
                hashMatch(filter)
            },
            background = background::addLast,
            dispatch = dispatched::addLast,
            pageSize = 10,
            prefetch = 3,
            seekLimit = 20,
            loadPending = { root ->
                pendingLoads.add(root)
                pendingPicks[root]
            },
        )

    /** A first-parent line of [count] commits; every fourth also merges the commit two below it. */
    private fun commits(
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

    /** The graph rows of [commits] laid out at once. */
    private fun graph(commits: List<GitCommit>): List<GraphRow> {
        val layout = GraphLayout()
        return commits.map { layout.next(it.hash, it.parents) }
    }

    /** Runs the background work, then what it dispatched, as the executor and the JavaFX thread would. */
    private fun settle() {
        while (background.isNotEmpty() || dispatched.isNotEmpty()) {
            while (background.isNotEmpty()) background.removeFirst().run()
            while (dispatched.isNotEmpty()) dispatched.removeFirst().run()
        }
    }

    private fun hashes() = pages.rows.map { it.commit.hash }

    @Test
    fun `loads nothing until visible, then the first page once`() {
        pages.open(repo)
        settle()
        assertEquals(emptyList(), loads)
        assertEquals(GitLogState.IDLE, pages.state.get())

        pages.visible = true
        assertEquals(GitLogState.LOADING, pages.state.get())
        settle()
        pages.visible = false
        pages.visible = true
        settle()

        assertEquals(listOf(Triple(repo, 0, 10)), loads)
        assertEquals((1..10).map { "r$it" }, hashes())
        assertEquals(GitLogState.LOADED, pages.state.get())
    }

    @Test
    fun `loads the next page near the end until a page is complete`() {
        pages.open(repo)
        pages.visible = true
        settle()

        pages.shown(6)
        settle()
        assertEquals(1, loads.size, "not near the end yet")

        pages.shown(7)
        pages.shown(8)
        settle()
        assertEquals(listOf(Triple(repo, 0, 10), Triple(repo, 10, 10)), loads, "one load while one is running")
        assertEquals(20, pages.rows.size)

        pages.shown(19)
        settle()
        assertEquals(25, pages.rows.size)
        pages.shown(24)
        settle()
        assertEquals(3, loads.size, "no more pages after a complete one")
        assertEquals((1..25).map { "r$it" }, hashes())
    }

    @Test
    fun `reads the cherry-pick or revert in progress with every first page`() {
        val pick = GitPendingPick(GitPick.CHERRY_PICK, "r3", listOf("a.txt"))
        pendingPicks[repo] = pick
        pages.open(repo)
        pages.visible = true
        settle()
        assertEquals(pick, pages.pending)

        pages.shown(9)
        settle()
        assertEquals(listOf(repo), pendingLoads, "not with further pages")

        pendingPicks.remove(repo)
        pages.refresh()
        settle()
        assertNull(pages.pending)
        assertEquals(listOf(repo, repo), pendingLoads)

        pendingPicks[repo] = pick
        pages.refresh()
        settle()
        assertEquals(pick, pages.pending)
        val commits = history.remove(repo)
        pages.refresh()
        settle()
        assertEquals(GitLogState.FAILED, pages.state.get())
        assertNull(pages.pending, "forgotten with a failed first page")
        history[repo] = checkNotNull(commits)

        pages.refresh()
        settle()
        pages.open(other)
        assertNull(pages.pending, "not for another repository")
    }

    @Test
    fun `refreshing while hidden only marks the log stale`() {
        pages.open(repo)
        pages.visible = true
        settle()
        pages.visible = false

        pages.refresh()
        settle()
        assertEquals(1, loads.size)

        pages.visible = true
        settle()
        assertEquals(2, loads.size)
    }

    @Test
    fun `a reload replaces the rows and keeps the selection by hash`() {
        pages.open(repo)
        pages.visible = true
        settle()
        pages.shown(9)
        settle()
        val selected = mutableListOf<List<Int>>()
        pages.selectedHashes = { listOf("r3", "r15", "gone") }
        pages.select = { selected += it }
        history[repo] = commits("new", 1) + commits("r", 25)

        pages.refresh()
        settle()

        assertEquals(listOf("new1") + (1..9).map { "r$it" }, hashes())
        assertEquals(listOf(listOf(3)), selected, "r15 is no longer loaded")
    }

    @Test
    fun `an empty repository says so, a failure keeps the rows`() {
        history[other] = emptyList()
        pages.visible = true
        pages.open(other)
        settle()
        assertEquals(GitLogState.EMPTY, pages.state.get())
        assertTrue(pages.rows.isEmpty())

        pages.open(repo)
        settle()
        history.remove(repo)
        pages.refresh()
        settle()
        assertEquals(GitLogState.FAILED, pages.state.get())
        assertEquals(10, pages.rows.size)

        pages.shown(9)
        settle()
        assertEquals(3, loads.size, "no paging after a failure")
    }

    @Test
    fun `results for another folder or of a superseded run are dropped`() {
        pages.visible = true
        pages.open(repo)
        pages.open(other)
        settle()
        assertEquals(listOf("o1", "o2", "o3"), hashes())
        assertEquals(listOf(Triple(other, 0, 10)), loads, "the superseded run did not even start git")

        // A run that already started: its result comes after the folder changed.
        pages.open(repo)
        background.removeFirst().run()
        pages.open(other)
        settle()
        assertEquals(listOf("o1", "o2", "o3"), hashes())

        pages.open(null)
        settle()
        assertTrue(pages.rows.isEmpty())
        assertEquals(GitLogState.IDLE, pages.state.get())
    }

    @Test
    fun `a page loaded after a refresh started is dropped`() {
        pages.open(repo)
        pages.visible = true
        settle()
        pages.shown(9)
        background.removeFirst().run()
        pages.refresh()
        settle()

        assertEquals((1..10).map { "r$it" }, hashes())
    }

    @Test
    fun `rows carry the commit graph, continued by further pages and restarted by a reload`() {
        pages.open(repo)
        pages.visible = true
        settle()
        assertEquals(graph(commits("r", 25).take(10)), pages.rows.map { it.graph })

        pages.shown(9)
        settle()
        pages.shown(19)
        settle()
        assertEquals(graph(commits("r", 25)), pages.rows.map { it.graph }, "as if laid out at once")
        assertTrue(pages.rows.any { it.graph.width > 1 }, "the merges open lanes across the page boundaries")

        history[repo] = commits("new", 1) + commits("r", 25)
        pages.refresh()
        settle()
        assertEquals(graph(history.getValue(repo).take(10)), pages.rows.map { it.graph })

        pages.open(other)
        settle()
        assertEquals(graph(commits("o", 3)), pages.rows.map { it.graph })
    }

    private fun ref(
        name: String,
        kind: GitRefKind,
        commit: String,
    ) = GitRef(name, kind, commit)

    @Test
    fun `reads the refs with every first page and gives them to the rows of all pages`() {
        val head = ref("HEAD", GitRefKind.HEAD, "r1")
        val main = ref("refs/heads/main", GitRefKind.LOCAL_BRANCH, "r1")
        val old = ref("refs/tags/old", GitRefKind.TAG, "r15")
        refs[repo] = GitRefs(listOf(head, main, old), "refs/heads/main")
        pages.open(repo)
        pages.visible = true
        settle()

        assertEquals(listOf(repo), refLoads)
        assertEquals(listOf(head, main), pages.rows[0].refs)
        assertEquals("refs/heads/main", pages.rows[0].currentBranch)
        assertTrue(pages.rows.drop(1).all { it.refs.isEmpty() && it.currentBranch == null })

        pages.shown(9)
        settle()
        assertEquals(listOf(repo), refLoads, "a further page reuses the refs")
        assertEquals(listOf(old), pages.rows[14].refs)
        assertNull(pages.rows[14].currentBranch, "HEAD is not on this row")

        val moved = ref("refs/heads/main", GitRefKind.LOCAL_BRANCH, "r2")
        refs[repo] = GitRefs(listOf(ref("HEAD", GitRefKind.HEAD, "r2"), moved), "refs/heads/main")
        pages.refresh()
        settle()
        assertEquals(listOf(repo, repo), refLoads, "a reload reads them again")
        assertEquals(emptyList(), pages.rows[0].refs)
        assertEquals(listOf("HEAD", "refs/heads/main"), pages.rows[1].refs.map { it.name })
    }

    @Test
    fun `rows have no refs if they cannot be read, and refs are not read after a failed page`() {
        pages.open(repo)
        pages.visible = true
        settle()
        assertEquals(10, pages.rows.size)
        assertTrue(pages.rows.all { it.refs.isEmpty() })

        history.remove(repo)
        pages.refresh()
        settle()
        assertEquals(listOf(repo), refLoads, "the page failed first")
    }

    @Test
    fun `a detached HEAD gives no current branch`() {
        refs[repo] = GitRefs(listOf(ref("HEAD", GitRefKind.HEAD, "r3")), null)
        pages.open(repo)
        pages.visible = true
        settle()

        assertEquals(listOf("HEAD"), pages.rows[2].refs.map { it.name })
        assertNull(pages.rows[2].currentBranch)
    }

    @Test
    fun `passes the filter to every load, a change reloads the first page and keeps the selection`() {
        pages.open(repo)
        pages.visible = true
        settle()
        val selected = mutableListOf<List<Int>>()
        pages.selectedHashes = { listOf("r12") }
        pages.select = { selected += it }

        pages.filter = GitLogFilter(text = "Commit 1")
        assertEquals(GitLogState.LOADING, pages.state.get())
        assertEquals(10, pages.rows.size, "the rows stay until the new ones are there")
        settle()
        assertEquals(listOf("r1") + (10..18).map { "r$it" }, hashes())
        assertEquals(listOf(listOf(3)), selected)
        assertEquals(listOf(GitLogFilter(), GitLogFilter(text = "Commit 1")), filters)

        pages.filter = GitLogFilter(text = "Commit 1")
        settle()
        assertEquals(2, loads.size, "the same filter again loads nothing")

        pages.shown(9)
        settle()
        assertEquals(Triple(repo, 10, 10), loads.last())
        assertEquals(GitLogFilter(text = "Commit 1"), filters.last())
        assertEquals(listOf("r1") + (10..19).map { "r$it" }, hashes())

        pages.refresh()
        settle()
        assertEquals(GitLogFilter(text = "Commit 1"), filters.last(), "a refresh keeps the filter")
    }

    @Test
    fun `a filter change while hidden marks the log stale and drops loads for the earlier filter`() {
        pages.open(repo)
        pages.visible = true
        pages.visible = false
        pages.filter = GitLogFilter(text = "Commit 2")
        settle()
        assertEquals(emptyList(), loads, "the load for the earlier filter did not even start")

        pages.visible = true
        settle()
        assertEquals(listOf(GitLogFilter(text = "Commit 2")), filters)
        assertEquals(listOf("r2") + (20..25).map { "r$it" }, hashes())
    }

    @Test
    fun `the commit named by the text as a hash is the first row and not repeated, paging counts git's commits`() {
        val all = commits("r", 25)
        passes = { _, _ -> true }
        hashMatch = { filter -> all.single { it.hash == "r12" }.takeIf { filter.text == "abcd" } }
        pages.open(repo)
        pages.visible = true
        settle()
        assertEquals(emptyList(), hashLoads, "not a hash")

        pages.filter = GitLogFilter(text = " abcd ")
        settle()
        assertEquals(listOf(GitLogFilter(text = " abcd ")), hashLoads)
        // Not the trimmed text: the fake only matches "abcd".
        assertEquals((1..10).map { "r$it" }, hashes())

        pages.filter = GitLogFilter(text = "abcd")
        settle()
        assertEquals(listOf("r12") + (1..10).map { "r$it" }, hashes())

        pages.shown(10)
        settle()
        assertEquals(Triple(repo, 10, 10), loads.last(), "skips the 10 commits git gave, not the 11 rows")
        assertEquals(listOf("r12") + (1..11).map { "r$it" } + (13..20).map { "r$it" }, hashes())

        hashMatch = { all.single { it.hash == "r5" } }
        pages.refresh()
        settle()
        assertEquals(listOf("r5") + (1..4).map { "r$it" } + (6..10).map { "r$it" }, hashes())
        assertEquals(GraphRow.NONE, pages.rows.first().graph)
    }

    @Test
    fun `rows have no graph under filters that hide commits, and follow only the first parent if asked`() {
        passes = { _, _ -> true }
        pages.open(repo)
        pages.visible = true
        for (filter in listOf(
            GitLogFilter(text = "C"),
            GitLogFilter(users = listOf("A")),
            GitLogFilter(noMerges = true),
        )) {
            pages.filter = filter
            settle()
            assertEquals(10, pages.rows.size)
            assertTrue(pages.rows.all { it.graph == GraphRow.NONE }, "$filter")
        }

        pages.filter = GitLogFilter(firstParent = true)
        settle()
        val layout = GraphLayout()
        val firstParents = commits("r", 25).take(10).map { layout.next(it.hash, it.parents.take(1)) }
        assertEquals(firstParents, pages.rows.map { it.graph })
        assertTrue(pages.rows.all { it.graph.width == 1 }, "no lanes for the merged commits")
        assertEquals(
            2,
            pages.rows[3]
                .commit.parents.size,
            "the row keeps all parents",
        )

        pages.filter = GitLogFilter(revisions = listOf("HEAD"), paths = listOf("a"))
        settle()
        assertEquals(graph(commits("r", 25).take(10)), pages.rows.map { it.graph })
    }

    @Test
    fun `an empty result says that no commits match the filters, or that there are none`() {
        pages.open(repo)
        pages.visible = true
        pages.filter = GitLogFilter(text = "nothing")
        settle()
        assertEquals(GitLogState.NO_MATCH, pages.state.get())
        assertEquals("No commits match the filters.", pages.state.get().message)

        history[repo] = emptyList()
        pages.filter = GitLogFilter(regex = true, matchCase = true)
        settle()
        assertEquals(GitLogState.EMPTY, pages.state.get(), "no text to match")
    }

    @Test
    fun `opening a folder resets the filter`() {
        pages.open(repo)
        pages.visible = true
        pages.filter = GitLogFilter(text = "Commit 1", noMerges = true)
        settle()

        pages.open(other)
        assertEquals(GitLogFilter(), pages.filter)
        settle()
        assertEquals(GitLogFilter(), filters.last())
        assertEquals(listOf("o1", "o2", "o3"), hashes())
        assertEquals(2, loads.size, "one load per folder")

        pages.filter = GitLogFilter(text = "x")
        pages.open(null)
        assertEquals(GitLogFilter(), pages.filter)
    }

    @Test
    fun `exposes the refs of the last first page`() {
        val head = ref("HEAD", GitRefKind.HEAD, "r1")
        refs[repo] = GitRefs(listOf(head), null)
        pages.open(repo)
        assertNull(pages.refs)
        pages.visible = true
        settle()
        assertEquals(GitRefs(listOf(head), null), pages.refs)

        pages.open(other)
        assertNull(pages.refs)
    }

    /** What [GitLogPages.loadUntil] told: the index found, or why it is missing. */
    private val seeks = mutableListOf<Any>()

    private fun loadUntil(hash: String) = pages.loadUntil(hash, { seeks += it }, { seeks += it })

    @Test
    fun `loadUntil finds a loaded commit at once and loads pages for a later one`() {
        pages.open(repo)
        pages.visible = true
        settle()

        loadUntil("r4")
        assertEquals(listOf<Any>(3), seeks)

        loadUntil("r15")
        settle()
        assertEquals(listOf<Any>(3, 14), seeks)
        assertEquals(listOf(Triple(repo, 0, 10), Triple(repo, 10, 10)), loads)
        assertEquals(20, pages.rows.size)
    }

    @Test
    fun `loadUntil waits for the first page`() {
        pages.open(repo)
        pages.visible = true

        loadUntil("r2")
        assertEquals(emptyList(), seeks)
        settle()

        assertEquals(listOf<Any>(1), seeks)
    }

    @Test
    fun `loadUntil says when the complete log does not have the commit, or the limit is reached`() {
        history[repo] = commits("r", 15)
        pages.open(repo)
        pages.visible = true
        settle()

        loadUntil("gone")
        settle()
        assertEquals(listOf<Any>(CommitMissing.NOT_IN_LOG), seeks)
        assertEquals(15, pages.rows.size)

        history[repo] = commits("r", 25)
        pages.refresh()
        settle()
        seeks.clear()
        loadUntil("r23")
        settle()
        assertEquals(listOf<Any>(CommitMissing.BEYOND_LIMIT), seeks)
        assertEquals(20, pages.rows.size, "no page beyond the limit")
    }

    @Test
    fun `loadUntil goes on after a refresh, in the new rows`() {
        pages.open(repo)
        pages.visible = true
        settle()

        loadUntil("r15")
        assertEquals("r15", pages.loadingUntil)
        pages.refresh()
        history[repo] = commits("new", 2) + commits("r", 25)
        settle()
        assertNull(pages.loadingUntil)

        assertEquals(listOf<Any>(16), seeks)
        // The page asked for before the refresh is dropped unread.
        assertEquals(listOf(Triple(repo, 0, 10), Triple(repo, 0, 10), Triple(repo, 10, 10)), loads)
    }

    @Test
    fun `loadUntil ends without telling on open, a filter change, a failure or a newer call`() {
        pages.open(repo)
        pages.visible = true
        settle()

        loadUntil("r15")
        pages.filter = GitLogFilter(text = "Commit")
        settle()
        assertEquals(emptyList(), seeks, "a filter change")

        loadUntil("r15")
        pages.open(repo)
        settle()
        assertEquals(emptyList(), seeks, "open")

        loadUntil("r15")
        loadUntil("r12")
        settle()
        assertEquals(listOf<Any>(11), seeks, "replaced")

        seeks.clear()
        pages.open(repo)
        settle()
        loadUntil("r15")
        pages.stopLoadingUntil()
        settle()
        assertEquals(emptyList(), seeks, "stopped")
        assertEquals(20, pages.rows.size, "the page asked for still comes")

        pages.open(repo)
        settle()
        history.remove(repo)
        loadUntil("r15")
        settle()
        assertEquals(GitLogState.FAILED, pages.state.get())
        assertEquals(emptyList(), seeks, "a failure")
    }

    @Test
    fun `loadUntil while a page loads waits for it`() {
        pages.open(repo)
        pages.visible = true
        settle()

        pages.shown(9)
        loadUntil("r12")
        settle()

        assertEquals(listOf<Any>(11), seeks)
        assertEquals(2, loads.size)
    }
}
