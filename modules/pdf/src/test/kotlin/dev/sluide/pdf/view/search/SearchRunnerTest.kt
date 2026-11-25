package dev.sluide.pdf.view.search

import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchRunnerTest {
    private val queued = ArrayDeque<Runnable>()
    private val fxQueue = ArrayDeque<Runnable>()
    private val runner = SearchRunner(Executor { queued += it }) { fxQueue += it }
    private val delivered = mutableListOf<String>()

    private fun submit(name: String) = runner.submit({ name }) { delivered += it }

    private fun drain() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
        while (fxQueue.isNotEmpty()) fxQueue.removeFirst().run()
    }

    @Test
    fun `a lone search runs on the executor and is delivered on the FX runner`() {
        submit("only")
        assertEquals(1, queued.size)
        queued.removeFirst().run()
        assertEquals(emptyList(), delivered)
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
    fun `a search cancelled before a newer one stays dropped`() {
        submit("old")
        runner.cancel()
        submit("new")
        drain()
        assertEquals(listOf("new"), delivered)
    }
}
