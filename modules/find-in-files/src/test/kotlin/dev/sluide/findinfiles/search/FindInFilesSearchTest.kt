package dev.sluide.findinfiles.search

import dev.sluide.findinfiles.LogCapture
import dev.sluide.textediting.SearchOptions
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executor
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindInFilesSearchTest {
    private class Timer(
        val delay: Duration,
        val task: Runnable,
    ) {
        var closed = false
    }

    private val timers = mutableListOf<Timer>()
    private val backgroundTasks = mutableListOf<Runnable>()
    private val fxTasks = mutableListOf<Runnable>()
    private var now = 0L

    /** What the listener heard, in order. */
    private val events = mutableListOf<String>()

    /** The queries walked and the cancelled flags their walks were given. */
    private val walks = mutableListOf<Pair<String, () -> Boolean>>()

    /** What a walk does; by default it finds one file named after the query. */
    private var walkScript: (
        SearchRequest,
        () -> Boolean,
        (FileHits) -> Unit,
    ) -> SearchOutcome? = { request, _, onFile ->
        onFile(hits(request.query))
        SearchOutcome.Done(1, 1, 0, totalCapped = false, visitCapped = false)
    }

    private val search =
        FindInFilesSearch(
            scheduler = {
                delay,
                task,
                ->
                Timer(delay, task).also { timers += it }.let { AutoCloseable { it.closed = true } }
            },
            background = Executor { backgroundTasks += it },
            runOnFx = { fxTasks += it },
            nanoTime = { now },
            walk = { request, cancelled, onFile ->
                walks += request.query to cancelled
                walkScript(request, cancelled, onFile)
            },
            listener =
                object : SearchListener {
                    override fun cleared() {
                        events += "cleared"
                    }

                    override fun started() {
                        events += "started"
                    }

                    override fun added(
                        files: List<FileHits>,
                        status: String,
                    ) {
                        events += "added ${files.map { it.file.fileName }} $status"
                    }

                    override fun finished(
                        outcome: SearchOutcome,
                        status: String,
                    ) {
                        events += "finished $status"
                    }

                    override fun failed() {
                        events += "failed"
                    }
                },
        )

    private fun request(query: String) = SearchRequest(listOf(Path.of("/root")), query, SearchOptions(), "", emptyMap())

    /** A file named [name] whose text is its name, matched once, built with the walk's own line builder. */
    private fun hits(name: String) =
        FileHits(Path.of("/root", name), Path.of("/root"), lineHits(name, listOf(name.indices)), 1, capped = false)

    private fun fireTimers() {
        timers.filter { !it.closed }.forEach {
            it.closed = true
            it.task.run()
        }
    }

    private fun runBackground() = backgroundTasks.toList().also { backgroundTasks.clear() }.forEach(Runnable::run)

    private fun runFx() = fxTasks.toList().also { fxTasks.clear() }.forEach(Runnable::run)

    private fun settle() {
        fireTimers()
        runBackground()
        runFx()
    }

    @Test
    fun `typing restarts the wait and only the last query is searched`() {
        search.changed(request("ab"))
        search.changed(request("abc"))

        assertTrue(walks.isEmpty())
        assertEquals(listOf(true, false), timers.map { it.closed })
        assertEquals(listOf(Duration.ofMillis(250), Duration.ofMillis(250)), timers.map { it.delay })

        settle()

        assertEquals(listOf("abc"), walks.map { it.first })
        assertEquals(listOf("started", "added [abc] 1 match in 1 file…", "finished 1 match in 1 file"), events)
    }

    @Test
    fun `a query under two characters clears and cancels the running walk`() {
        search.runNow(request("ab"))
        search.changed(request("a"))
        settle()

        assertEquals(listOf("started", "cleared"), events)
        assertEquals(true, walks.single().second())
        assertTrue(timers.isEmpty())
    }

    @Test
    fun `a query under two characters drops the pending wait`() {
        search.changed(request("ab"))
        search.changed(request("a"))
        settle()

        assertEquals(listOf("cleared"), events)
        assertTrue(walks.isEmpty())
    }

    @Test
    fun `blanks count towards the length and are searched as typed`() {
        search.changed(request(" a"))
        settle()

        assertEquals(listOf(" a"), walks.map { it.first })
    }

    @Test
    fun `running a query under two characters at once clears`() {
        search.runNow(request("ab"))
        search.runNow(request("a"))
        settle()

        assertEquals(listOf("started", "cleared"), events)
        assertEquals(listOf("ab"), walks.map { it.first })
    }

    @Test
    fun `the results of a replaced walk never arrive`() {
        search.runNow(request("old"))
        search.changed(request("new"))
        fireTimers()
        // Both walks run only now, the replaced one first, and post their results.
        runBackground()
        runFx()

        assertEquals(listOf("old" to true, "new" to false), walks.map { it.first to it.second() })
        assertEquals(
            listOf("started", "started", "added [new] 1 match in 1 file…", "finished 1 match in 1 file"),
            events,
        )
    }

    @Test
    fun `cancelling drops the results of the running walk`() {
        search.runNow(request("ab"))
        search.cancel()
        settle()

        assertEquals(listOf("started"), events)
    }

    @Test
    fun `running now skips the wait`() {
        search.runNow(request("ab"))

        assertTrue(timers.isEmpty())
        assertEquals(listOf("started"), events)
        assertEquals(1, backgroundTasks.size)
    }

    @Test
    fun `files arrive in batches at most once per interval, the last ones before the end`() {
        walkScript = { _, cancelled, onFile ->
            onFile(hits("a"))
            now = 50_000_000
            onFile(hits("b"))
            now = 99_000_000
            cancelled()
            now = 100_000_000
            onFile(hits("c"))
            now = 150_000_000
            cancelled()
            onFile(hits("d"))
            SearchOutcome.Done(4, 4, 0, totalCapped = false, visitCapped = false)
        }
        search.runNow(request("ab"))
        runBackground()

        assertEquals(3, fxTasks.size)
        runFx()
        assertEquals(
            listOf(
                "started",
                "added [a] 1 match in 1 file…",
                "added [b, c] 3 matches in 3 files…",
                "added [d] 4 matches in 4 files…",
                "finished 4 matches in 4 files",
            ),
            events,
        )
    }

    @Test
    fun `a due batch is also posted while the walk only asks whether it is cancelled`() {
        walkScript = { _, cancelled, onFile ->
            onFile(hits("a"))
            onFile(hits("b"))
            now = 100_000_000
            cancelled()
            null
        }
        search.runNow(request("ab"))
        runBackground()
        runFx()

        assertEquals(listOf("started", "added [a] 1 match in 1 file…", "added [b] 2 matches in 2 files…"), events)
    }

    @Test
    fun `asking whether the walk is cancelled before it found a file starts no interval`() {
        walkScript = { _, cancelled, onFile ->
            cancelled()
            now = 10_000_000
            onFile(hits("a"))
            null
        }
        search.runNow(request("ab"))
        runBackground()
        runFx()

        assertEquals(listOf("started", "added [a] 1 match in 1 file…"), events)
    }

    @Test
    fun `the real clock separates the batches by the interval`() {
        var spun = 0L
        val timed =
            FindInFilesSearch(
                scheduler = { _, _ -> AutoCloseable { } },
                background = Executor { backgroundTasks += it },
                runOnFx = { fxTasks += it },
                batchEvery = Duration.ofNanos(1),
                walk = { _, _, onFile ->
                    onFile(hits("a"))
                    // Waits for the clock to move on, never longer than a tick of it.
                    val before = System.nanoTime()
                    while (System.nanoTime() == before) spun++
                    onFile(hits("b"))
                    SearchOutcome.Done(2, 2, 0, totalCapped = false, visitCapped = false)
                },
                listener = NoListener,
            )

        timed.runNow(request("ab"))
        runBackground()

        assertEquals(3, fxTasks.size, "a batch for each file, then the end")
    }

    @Test
    fun `a finished walk logs its counts and its time`() {
        walkScript = { _, _, _ ->
            now = 12_000_000
            SearchOutcome.Done(3, 2, 1, totalCapped = false, visitCapped = false)
        }
        now = 5_000_000

        LogCapture(FindInFilesSearch::class.java.name).use { log ->
            search.runNow(request("ab"))
            runBackground()

            assertEquals(
                listOf(Level.INFO to "Find in Files: 3 matches in 2 files, 1 skipped, in 7 ms"),
                log.lines,
            )
        }
    }

    @Test
    fun `an invalid regex logs nothing, a failed walk logs the error`() {
        walkScript = { request, _, _ -> if (request.query == "(ab") SearchOutcome.Invalid("x") else error("boom") }

        LogCapture(FindInFilesSearch::class.java.name).use { log ->
            search.runNow(request("(ab"))
            runBackground()
            assertEquals(emptyList(), log.lines)

            search.runNow(request("abc"))
            runBackground()
            assertEquals(listOf(Level.SEVERE to "Find in Files failed"), log.lines.map { it.first to it.second })
            assertEquals("boom", log.thrown.single().message)
        }
    }

    @Test
    fun `a walk that throws fails without results`() {
        walkScript = { _, _, onFile ->
            onFile(hits("a"))
            onFile(hits("b"))
            error("boom")
        }
        search.runNow(request("ab"))
        settle()

        assertEquals(listOf("started", "added [a] 1 match in 1 file…", "failed"), events)
    }

    @Test
    fun `a replaced walk that throws neither fails nor ends the walk that replaced it`() {
        walkScript = { request, _, _ -> if (request.query == "old") error("boom") else null }
        search.runNow(request("old"))
        search.changed(request("new"))
        fireTimers()
        runBackground()
        runFx()

        assertEquals(listOf("started", "started"), events)
        search.cancel()
        assertEquals(listOf("old" to true, "new" to true), walks.map { it.first to it.second() })
    }

    @Test
    fun `an invalid regex finishes with its message`() {
        walkScript = { _, _, _ -> SearchOutcome.Invalid("Unclosed group") }
        search.runNow(request("(ab"))
        settle()

        assertEquals(listOf("started", "finished Unclosed group"), events)
    }

    @Test
    fun `a scheduler without delay starts the walk at once`() {
        val immediate =
            FindInFilesSearch(
                scheduler = { _, task ->
                    task.run()
                    AutoCloseable { error("closed after it ran") }
                },
                background = Executor { backgroundTasks += it },
                runOnFx = { fxTasks += it },
                walk = { request, cancelled, _ ->
                    walks += request.query to cancelled
                    null
                },
                listener = NoListener,
            )

        immediate.changed(request("ab"))
        immediate.cancel()
        runBackground()

        assertEquals(listOf("ab" to true), walks.map { it.first to it.second() })
    }

    private object NoListener : SearchListener {
        override fun cleared() = Unit

        override fun started() = Unit

        override fun added(
            files: List<FileHits>,
            status: String,
        ) = Unit

        override fun finished(
            outcome: SearchOutcome,
            status: String,
        ) = Unit

        override fun failed() = Unit
    }
}
