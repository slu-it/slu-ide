package dev.sluide.ebooks.view

import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchRunnerTest {
    private val queued = ArrayDeque<Runnable>()
    private val fxQueue = ArrayDeque<Runnable>()
    private val runner = SearchRunner(Executor { queued += it }) { fxQueue += it }
    private val delivered = mutableListOf<String>()
    private var attached = true

    private fun submit(name: String) = runner.submit({ name }, { attached }) { delivered += it }

    private fun drain() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
        while (fxQueue.isNotEmpty()) fxQueue.removeFirst().run()
    }

    private fun result(vararg chapters: Int) =
        SearchResult(chapters.map { SearchMatch(it, "C$it", 0, 1, "", "x", "") }, false)

    @Test
    fun `a lone search is delivered`() {
        submit("only")
        drain()
        assertEquals(listOf("only"), delivered)
    }

    @Test
    fun `only the newest search is delivered`() {
        submit("old")
        submit("new")
        drain()
        assertEquals(listOf("new"), delivered)
    }

    @Test
    fun `a cancelled search delivers nothing`() {
        submit("a")
        runner.cancel()
        drain()
        assertEquals(emptyList(), delivered)
    }

    @Test
    fun `a result that arrives while detached is dropped`() {
        submit("a")
        attached = false
        drain()
        assertEquals(emptyList(), delivered)
    }

    @Test
    fun `rows group the hits under a heading per chapter`() {
        val rows = searchRows(result(1, 1, 4))
        assertEquals(
            listOf("H:C1", "Hit", "Hit", "H:C4", "Hit"),
            rows.map { if (it is SearchRow.Heading) "H:${it.title}" else "Hit" },
        )
        assertEquals(emptyList(), searchRows(result()))
    }

    @Test
    fun `the status names the count, the cap and no matches`() {
        assertEquals("No matches", searchStatus(result()))
        assertEquals("1 match", searchStatus(result(0)))
        assertEquals("2 matches", searchStatus(result(0, 0)))
        assertEquals("1000+ matches", searchStatus(SearchResult(List(1000) { result(0).matches[0] }, true)))
    }
}
