package dev.sluide.fileexplorer.search

import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class ExplorerSearchTest {
    private val statuses = mutableListOf<String>()
    private val applied = mutableListOf<List<Path>?>()
    private var extrasResets = 0
    private var active = false
    private var cancelledFlags = mutableListOf<() -> Boolean>()
    private val walked = mutableListOf<String>()
    private var nextResult: SearchResult? = SearchResult(emptyList(), false)
    private var failure: RuntimeException? = null

    private val scheduled = mutableListOf<Pair<Runnable, BooleanArray>>()
    private val backgroundTasks = mutableListOf<Runnable>()
    private val fxTasks = mutableListOf<Runnable>()
    private val delays = mutableListOf<Duration>()

    private val search =
        ExplorerSearch(
            scheduler =
                SearchScheduler { delay, task ->
                    delays += delay
                    val entry = task to booleanArrayOf(false)
                    scheduled += entry
                    AutoCloseable { entry.second[0] = true }
                },
            delay = { Duration.parse("250ms") },
            background = Executor { backgroundTasks += it },
            runOnFx = { fxTasks += it },
            roots = { listOf(Path.of("root")) },
            walk = { _, _, cancelled ->
                cancelledFlags += cancelled
                walked += "walk"
                failure?.let { throw it }
                nextResult
            },
            apply = {
                applied += it
                active = it != null
            },
            resetExtras = { extrasResets++ },
            isActive = { active },
            onStatus = { statuses += it },
        )

    /** Runs the timers that were not cancelled, then the background walks, then the FX tasks. */
    private fun settle() {
        scheduled.filter { !it.second[0] }.forEach {
            it.second[0] = true
            it.first.run()
        }
        scheduled.clear()
        backgroundTasks.toList().also { backgroundTasks.clear() }.forEach(Runnable::run)
        fxTasks.toList().also { fxTasks.clear() }.forEach(Runnable::run)
    }

    private fun result(
        vararg names: String,
        truncated: Boolean = false,
    ) = SearchResult(names.map { Path.of("root", it) }, truncated)

    @Test
    fun `fewer than three characters start no walk and show the hint`() {
        search.textChanged("ab")
        settle()

        assertTrue(walked.isEmpty())
        assertTrue(applied.isEmpty())
        assertEquals(listOf("Type at least 3 characters"), statuses)
    }

    @Test
    fun `dropping below three characters after a search restores the tree`() {
        nextResult = result("a.kt")
        search.textChanged("abc")
        settle()
        assertTrue(active)

        search.textChanged("ab")

        assertEquals(listOf(null), applied.drop(1))
        assertEquals("Type at least 3 characters", statuses.last())
        assertNull(search.query)
        assertTrue(search.matches.isEmpty())
    }

    @Test
    fun `an emptied field restores the tree and clears the status`() {
        nextResult = result("a.kt")
        search.textChanged("abc")
        settle()

        search.textChanged("")

        assertNull(applied.last())
        assertEquals("", statuses.last())
    }

    @Test
    fun `several keystrokes inside the delay cost one walk`() {
        search.textChanged("abc")
        search.textChanged("abcd")
        search.textChanged("abcde")
        settle()

        assertEquals(1, walked.size)
        assertEquals(listOf(Duration.parse("250ms")), delays.distinct())
    }

    @Test
    fun `a result of an older text is dropped`() {
        search.textChanged("abc")
        settle0Timers()
        // The walk of "abc" has finished and waits for the FX thread when the text changes.
        nextResult = result("old.kt")
        backgroundTasks.toList().also { backgroundTasks.clear() }.forEach(Runnable::run)
        search.textChanged("abcd")
        nextResult = result("new.kt")
        settle()

        assertEquals(listOf<List<Path>?>(listOf(Path.of("root", "new.kt"))), applied)
    }

    private fun settle0Timers() {
        scheduled.filter { !it.second[0] }.forEach {
            it.second[0] = true
            it.first.run()
        }
        scheduled.clear()
    }

    @Test
    fun `a new text cancels the running walk`() {
        search.textChanged("abc")
        settle0Timers()
        backgroundTasks.toList().also { backgroundTasks.clear() }.forEach(Runnable::run)
        assertFalse(cancelledFlags.last()())

        search.textChanged("abcd")

        assertTrue(cancelledFlags.last()())
    }

    @Test
    fun `the extras are reset when the text changed since the last walk, not when it is searched again`() {
        search.textChanged("abc")
        settle()
        assertEquals(1, extrasResets)

        search.invalidated()
        settle()
        assertEquals(2, walked.size)
        assertEquals(1, extrasResets)

        search.textChanged("abcd")
        settle()
        assertEquals(2, extrasResets)
    }

    @Test
    fun `an invalidation with less than three characters searches nothing`() {
        search.textChanged("ab")
        search.invalidated()
        settle()

        assertTrue(walked.isEmpty())
    }

    @Test
    fun `the status tells the walk, then the count`() {
        nextResult = result()
        search.textChanged("abc")
        settle()
        nextResult = result("a")
        search.invalidated()
        settle()
        nextResult = result("a", "b")
        search.invalidated()
        settle()

        // A search of the same text again keeps the count until its result comes.
        assertEquals(listOf("Searching…", "No matches", "1 match", "2 matches"), statuses)
    }

    @Test
    fun `the status of a capped search tells which cap ended it`() {
        nextResult = SearchResult((1..100).map { Path.of("root", "$it") }, truncated = true)
        search.textChanged("abc")
        settle()
        nextResult = result("a", "b", truncated = true)
        search.textChanged("abcd")
        settle()
        nextResult = result("a", truncated = true)
        search.textChanged("abcde")
        settle()

        assertEquals(
            listOf(
                "First 100 matches, type more to narrow",
                "2 matches in the first 200 000 entries, type more to narrow",
                "1 match in the first 200 000 entries, type more to narrow",
            ),
            statuses.filter { it != "Searching…" },
        )
    }

    @Test
    fun `a path found twice is applied once`() {
        nextResult = result("a", "b", "a")
        search.textChanged("abc")
        settle()

        assertEquals(listOf<List<Path>?>(listOf(Path.of("root", "a"), Path.of("root", "b"))), applied)
        assertEquals("2 matches", statuses.last())
    }

    @Test
    fun `invalidations during a walk of the same text neither cancel it nor change the status, one re-run follows`() {
        search.textChanged("abc")
        settle0Timers()
        nextResult = result("a")
        // The walk has returned and its result waits for the FX thread: the walk still counts as running.
        backgroundTasks.toList().also { backgroundTasks.clear() }.forEach(Runnable::run)
        repeat(5) { search.invalidated() }

        assertTrue(scheduled.isEmpty())
        assertFalse(cancelledFlags.last()())
        fxTasks.toList().also { fxTasks.clear() }.forEach(Runnable::run)

        assertEquals(1, applied.size)
        assertEquals(1, scheduled.size)

        settle()
        assertEquals(2, walked.size)
        assertEquals(listOf("Searching…", "1 match", "1 match"), statuses)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `a wait of the same text is not restarted by an invalidation`() {
        search.textChanged("abc")
        search.invalidated()
        search.invalidated()
        settle()

        assertEquals(1, walked.size)
    }

    @Test
    fun `an action waits for the results of the text typed so far, starting the walk at once`() {
        val order = mutableListOf<String>()
        search.textChanged("abc")
        settle()
        search.textChanged("abcd")

        search.whenCurrent { order += "action" }

        assertTrue(scheduled.all { it.second[0] }, "the wait is dropped")
        assertEquals(1, backgroundTasks.size)
        assertTrue(order.isEmpty())
        nextResult = result("a")
        backgroundTasks.toList().also { backgroundTasks.clear() }.forEach(Runnable::run)
        fxTasks.toList().also { fxTasks.clear() }.forEach(Runnable::run)

        assertEquals(listOf("action"), order)
        assertEquals(2, applied.size)
    }

    @Test
    fun `an action runs at once when the text is applied or too short, and is dropped by a newer text`() {
        val order = mutableListOf<String>()
        search.whenCurrent { order += "short" }
        search.textChanged("abc")
        settle()
        search.whenCurrent { order += "current" }
        assertEquals(listOf("short", "current"), order)

        search.textChanged("abcd")
        search.whenCurrent { order += "stale" }
        search.textChanged("abcde")
        settle()

        assertEquals(listOf("short", "current"), order)
    }

    @Test
    fun `a cancelled walk applies nothing`() {
        nextResult = null
        search.textChanged("abc")
        settle()

        assertTrue(applied.isEmpty())
    }

    @Test
    fun `a root change cancels the walk and the wait and applies nothing`() {
        search.textChanged("abc")
        settle0Timers()
        search.textChanged("abcd")
        search.rootsChanged()
        settle()

        assertTrue(cancelledFlags.all { it() })
        assertEquals(1, walked.size)
        assertTrue(applied.isEmpty())
        assertEquals("", statuses.last())
    }

    @Test
    fun `clearing the field after a root change does not restore the new tree`() {
        nextResult = result("a.kt")
        search.textChanged("abc")
        settle()
        applied.clear()
        search.rootsChanged()
        // Only the controller's own reset keeps this out: a tree that claims an active search is not restored.
        active = true

        search.textChanged("")

        assertTrue(applied.isEmpty())
    }

    @Test
    fun `a walk that throws frees the search, drops what waits for it and says so`() {
        val order = mutableListOf<String>()
        failure = IllegalStateException("git callback")
        search.textChanged("abc")
        settle0Timers()
        search.whenCurrent { order += "action" }
        search.invalidated()
        settle()

        assertEquals("Search failed", statuses.last())
        assertTrue(applied.isEmpty())
        assertTrue(order.isEmpty())

        // Nothing is stuck: the next change searches again, and the dropped action does not come back.
        failure = null
        nextResult = result("a")
        search.invalidated()
        settle()

        assertEquals(2, walked.size)
        assertEquals("1 match", statuses.last())
        assertTrue(order.isEmpty())
    }

    @Test
    fun `the first 100 matches note is decided before duplicates are removed`() {
        nextResult = SearchResult((1..100).map { Path.of("root", "${it % 50}") }, truncated = true)
        search.textChanged("abc")
        settle()

        assertEquals("First 100 matches, type more to narrow", statuses.last())
        assertEquals(50, applied.last()?.size)
    }

    @Test
    fun `a scheduler that runs the task at once leaves nothing pending`() {
        val inline = mutableListOf<Path>()
        val immediate =
            ExplorerSearch(
                scheduler =
                    SearchScheduler { _, task ->
                        task.run()
                        AutoCloseable { }
                    },
                delay = { Duration.ZERO },
                background = Executor { it.run() },
                runOnFx = { it.run() },
                roots = { listOf(Path.of("root")) },
                walk = { _, _, _ ->
                    inline.add(Path.of("walk"))
                    SearchResult(emptyList(), false)
                },
                apply = {},
                resetExtras = {},
                isActive = { false },
                onStatus = {},
            )
        immediate.textChanged("abc")
        immediate.invalidated()

        assertEquals(2, inline.size)
    }
}
