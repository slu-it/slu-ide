package dev.sluide.gitlog.history

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitFileHistoryEntry
import dev.sluide.git.GitFileHistoryPage
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.gitlog.data.GraphRow
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a file's history loads and selects (ADR 0046), without controls. */
class FileHistoryTest {
    private val repo = Path.of("/repo")

    /** The history of the file; `null` makes reading it fail. */
    private var entries: List<GitFileHistoryEntry>? = (1..12).map { entry(it, "a.txt") }

    /** The (start, skip, limit) of every load. */
    private val loads = mutableListOf<Triple<String?, Int, Int>>()
    private val cancellations = mutableListOf<GitCancellation>()

    /** Runs while a load reads, as if git were running. */
    private var loading: () -> Unit = {}
    private var refs: GitRefs? = null
    private val background = ArrayDeque<Runnable>()
    private val dispatched = ArrayDeque<Runnable>()

    /** The log lines of the background tasks of the loads, without their numbers and durations. */
    private val taskLines = CopyOnWriteArrayList<String>()
    private val tasks =
        BackgroundTasks.forTests { line ->
            taskLines += line.replace(Regex("task#\\d+"), "task").replace(Regex(" (after|in) \\d+(\\.\\d+)? s"), " $1")
        }

    /** The index of the selected row, as the table would have it. */
    private var selected = -1
    private var failures = 0

    private fun entry(
        number: Int,
        path: String,
        change: GitChange = GitChange.MODIFIED,
        oldPath: String? = null,
    ) = GitFileHistoryEntry(
        GitCommit("c$number", listOf("c${number + 1}"), "A", "a@example.com", Instant.EPOCH, "Subject $number"),
        GitChangedFile(change, path, oldPath),
    )

    private fun history(start: String? = null) =
        FileHistory(
            repo,
            FileHistoryKey("a.txt", start),
            { _, _, start, skip, limit, cancellation ->
                loads += Triple(start, skip, limit)
                cancellations += cancellation
                loading()
                // Git stopped gives nothing, as a failure.
                entries?.takeIf { !cancellation.cancelled }?.let { all ->
                    val page = all.drop(skip).take(limit)
                    GitFileHistoryPage(page, complete = skip + page.size >= all.size)
                }
            },
            { refs },
            background::addLast,
            dispatched::addLast,
            tasks,
            pageSize = 5,
            prefetch = 2,
        ).apply {
            selectedHash = { rows.getOrNull(selected)?.commit?.hash }
            select = { selected = it }
            failedWithRows = { failures++ }
        }

    /** Runs the background work, then what it dispatched, as the executor and the JavaFX thread would. */
    private fun settle() {
        while (background.isNotEmpty() || dispatched.isNotEmpty()) {
            while (background.isNotEmpty()) background.removeFirst().run()
            while (dispatched.isNotEmpty()) dispatched.removeFirst().run()
        }
    }

    private fun subjects(history: FileHistory) = history.rows.map { it.commit.subject }

    @Test
    fun `loads nothing until visible, then the first page with the first row selected`() {
        val history = history()
        settle()
        assertEquals(FileHistoryState.IDLE, history.state.get())
        assertTrue(loads.isEmpty())

        history.visible = true
        assertEquals(FileHistoryState.LOADING, history.state.get())
        settle()

        assertEquals(listOf(Triple<String?, Int, Int>(null, 0, 5)), loads)
        assertEquals((1..5).map { "Subject $it" }, subjects(history))
        assertEquals(FileHistoryState.LOADED, history.state.get())
        assertEquals(0, selected)
        assertEquals(GraphRow.NONE, history.rows.first().graph)
    }

    @Test
    fun `selects the start commit's row at first, and loads further pages near the end`() {
        // Git lists the start commit first if it changed the file; the row is found by its hash wherever it is.
        entries = (3..12).map { entry(it, "a.txt") }
        val history = history(start = "c5")
        history.visible = true
        settle()
        assertEquals(2, selected)
        assertEquals("c5", loads.single().first)

        history.shown(1)
        settle()
        assertEquals(1, loads.size, "not near the end yet")
        history.shown(3)
        settle()
        history.shown(8)
        settle()
        assertEquals(listOf(0, 5), loads.map { it.second })
        assertEquals(10, history.rows.size)
        // Complete: no more loads.
        history.shown(9)
        settle()
        assertEquals(2, loads.size)
    }

    @Test
    fun `a refresh reads as many rows again and keeps the selected commit, or none if it is gone`() {
        val history = history()
        history.visible = true
        settle()
        history.shown(4)
        settle()
        selected = 7

        entries = listOf(entry(0, "a.txt")) + entries.orEmpty()
        history.refresh()
        settle()

        assertEquals(Triple<String?, Int, Int>(null, 0, 10), loads.last())
        assertEquals("c8", history.rows[selected].commit.hash)

        entries = entries.orEmpty().filter { it.commit.hash != "c8" }
        history.refresh()
        settle()
        assertEquals(-1, selected)
    }

    @Test
    fun `a refresh while hidden waits until shown, after close nothing is read`() {
        val history = history()
        history.visible = true
        settle()
        history.visible = false
        history.refresh()
        settle()
        assertEquals(1, loads.size)
        history.visible = true
        settle()
        assertEquals(2, loads.size)

        history.close()
        history.refresh()
        settle()
        assertEquals(2, loads.size)
    }

    @Test
    fun `tells the file's path per row, and whether there are several`() {
        entries =
            listOf(
                entry(1, "b.txt"),
                entry(2, "b.txt", GitChange.RENAMED, "a.txt"),
                entry(3, "a.txt", GitChange.ADDED),
            )
        val history = history()
        history.visible = true
        settle()

        assertTrue(history.multiplePaths.get())
        assertEquals(listOf("b.txt", "b.txt", "a.txt"), history.rows.map { history.fileOf(it)?.path })
        assertEquals("a.txt", history.fileOf(history.rows[1])?.oldPath)

        entries = entries.orEmpty().take(1)
        history.refresh()
        settle()
        assertFalse(history.multiplePaths.get())
    }

    @Test
    fun `the rows have the refs pointing at their commits`() {
        val main = GitRef("refs/heads/main", GitRefKind.LOCAL_BRANCH, "c2")
        val head = GitRef("HEAD", GitRefKind.HEAD, "c2")
        refs = GitRefs(listOf(head, main), "refs/heads/main")
        val history = history()
        history.visible = true
        settle()

        assertEquals(listOf(head, main), history.rows[1].refs)
        assertEquals("refs/heads/main", history.rows[1].currentBranch)
        assertEquals(emptyList(), history.rows[0].refs)
        assertNull(history.rows[0].currentBranch)
    }

    @Test
    fun `an empty history, a failed one, and a failed refresh that keeps the rows`() {
        entries = emptyList()
        val empty = history()
        empty.visible = true
        settle()
        assertEquals(FileHistoryState.EMPTY, empty.state.get())
        assertEquals("No history: a.txt has no commits.", empty.state.get().message("a.txt"))
        assertEquals(-1, selected)

        entries = null
        val failed = history()
        failed.visible = true
        settle()
        assertEquals(FileHistoryState.FAILED, failed.state.get())
        assertEquals(0, failures)

        entries = listOf(entry(1, "a.txt"))
        failed.refresh()
        settle()
        entries = null
        failed.refresh()
        settle()
        assertEquals(FileHistoryState.FAILED, failed.state.get())
        assertEquals(1, failed.rows.size)
        assertEquals(1, failures)
    }

    @Test
    fun `every state has its placeholder text, and failures and cancels keep the rows`() {
        val messages = FileHistoryState.entries.associateWith { it.message("a.txt") }
        assertEquals(
            mapOf(
                FileHistoryState.IDLE to "",
                FileHistoryState.LOADING to "Loading…",
                FileHistoryState.LOADED to "",
                FileHistoryState.EMPTY to "No history: a.txt has no commits.",
                FileHistoryState.FAILED to "The history could not be read.",
                FileHistoryState.CANCELLED to "Loading cancelled. Refresh to load.",
            ),
            messages,
        )
        assertEquals(
            setOf(FileHistoryState.FAILED, FileHistoryState.CANCELLED),
            FileHistoryState.entries.filter { it.keepsRows }.toSet(),
        )
    }

    @Test
    fun `a load that throws is a failed page, not a history that keeps loading`() {
        val history = history()
        history.visible = true
        loading = { throw IllegalStateException("boom") }
        settle()

        assertEquals(FileHistoryState.FAILED, history.state.get())
        assertTrue(taskLines.last().startsWith("task failed after: Loading history of a.txt"), "$taskLines")

        loading = {}
        history.refresh()
        settle()
        assertEquals(FileHistoryState.LOADED, history.state.get())

        loading = { throw IllegalStateException("boom") }
        history.shown(4)
        settle()
        assertEquals(FileHistoryState.FAILED, history.state.get())
        loading = {}
        history.refresh()
        settle()
        assertEquals(FileHistoryState.LOADED, history.state.get(), "loading more is not stuck either")
    }

    @Test
    fun `after a failed page a refresh reads only the rows loaded before it`() {
        val history = history()
        history.visible = true
        settle()
        val all = entries
        entries = null
        history.shown(4)
        settle()
        assertEquals(FileHistoryState.FAILED, history.state.get())
        assertEquals(1, failures)
        history.shown(4)
        settle()
        assertEquals(2, loads.size, "no more pages after a failure")

        entries = all
        history.refresh()
        settle()
        assertEquals(Triple<String?, Int, Int>(null, 0, 5), loads.last())
        assertEquals(FileHistoryState.LOADED, history.state.get())
    }

    @Test
    fun `a newer load and closing cancel the git of the load going on`() {
        val history = history()
        var duringLoad: (() -> Unit)? = { history.refresh() }
        loading = {
            val action = duringLoad
            duringLoad = null
            action?.invoke()
        }
        history.visible = true
        settle()
        assertEquals(2, cancellations.size)
        assertTrue(cancellations[0].cancelled, "replaced while git ran")
        assertFalse(cancellations[1].cancelled)

        duringLoad = { history.close() }
        history.refresh()
        settle()
        assertTrue(cancellations.last().cancelled, "closed while git ran")
    }

    @Test
    fun `every load is a task, but not one dropped before it runs`() {
        val history = history()
        history.visible = true
        history.refresh()
        settle()
        history.shown(4)
        settle()
        entries = null
        history.refresh()
        settle()

        assertEquals(
            listOf(
                "task started: Loading history of a.txt",
                "task finished in: Loading history of a.txt",
                "task started: Loading history of a.txt",
                "task finished in: Loading history of a.txt",
                "task started: Loading history of a.txt",
                "task failed after: Loading history of a.txt: The history could not be read.",
            ),
            taskLines,
        )
        assertEquals(3, loads.size, "the first load was replaced before it ran")
    }

    @Test
    fun `cancelling a page keeps the rows and the next scroll loads it, a cancelled refresh says to refresh`() {
        val history = history()
        history.visible = true
        settle()
        loading = { tasks.shown.single().cancel() }
        history.shown(4)
        settle()

        assertEquals(FileHistoryState.LOADED, history.state.get())
        assertEquals((1..5).map { "Subject $it" }, subjects(history))
        assertEquals(0, failures)
        assertEquals(
            listOf("task cancelled", "task cancelled after: Loading history of a.txt"),
            taskLines.takeLast(2),
        )

        loading = {}
        history.shown(4)
        settle()
        assertEquals(10, history.rows.size, "the next scroll loads the page")

        loading = { tasks.shown.single().cancel() }
        history.refresh()
        settle()
        assertEquals(FileHistoryState.CANCELLED, history.state.get())
        assertEquals("Loading cancelled. Refresh to load.", history.state.get().message("a.txt"))
        assertEquals(10, history.rows.size)
        assertEquals(1, failures, "the header says it, as the rows stay")
        history.shown(9)
        settle()
        assertEquals(4, loads.size, "outdated rows load no further page")

        loading = {}
        history.refresh()
        settle()
        assertEquals(Triple<String?, Int, Int>(null, 0, 10), loads.last())
        assertEquals(FileHistoryState.LOADED, history.state.get())
    }

    @Test
    fun `a cancelled first load says to refresh, and a refresh or showing it again loads`() {
        val history = history()
        loading = { tasks.shown.single().cancel() }
        history.visible = true
        settle()

        assertEquals(FileHistoryState.CANCELLED, history.state.get())
        assertEquals("Loading cancelled. Refresh to load.", history.state.get().message("a.txt"))
        assertTrue(history.rows.isEmpty())
        assertEquals(0, failures, "the table's placeholder says it")
        assertEquals("task cancelled after: Loading history of a.txt", taskLines.last())

        loading = {}
        history.refresh()
        settle()
        assertEquals(FileHistoryState.LOADED, history.state.get())
        assertEquals(0, selected, "the first load's selection")

        loading = { tasks.shown.single().cancel() }
        history.refresh()
        settle()
        loading = {}
        history.visible = false
        history.visible = true
        settle()
        assertEquals(FileHistoryState.LOADED, history.state.get())
    }
}
