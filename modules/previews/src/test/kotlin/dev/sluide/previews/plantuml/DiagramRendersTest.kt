package dev.sluide.previews.plantuml

import dev.sluide.plantuml.Diagram
import dev.sluide.plantuml.PlantUml
import dev.sluide.plantuml.Rendering
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The renderings of one view: requested once, kept, and a rerender only for what the page asked for. */
class DiagramRendersTest {
    /** Every request in order, with its future, which the test completes. */
    private val requests = mutableListOf<Pair<Pair<String, Int>, CompletableFuture<Rendering>>>()

    /** What [DiagramRenders] hands to the FX thread, run by [runFx]. */
    private val fxQueue = ArrayDeque<Runnable>()

    private var rerenders = 0

    /** What the page asks for when it is rendered again. */
    private var page: DiagramRenders.() -> Unit = {}

    private fun renders(cacheSize: Int = 64): DiagramRenders {
        lateinit var renders: DiagramRenders
        renders =
            DiagramRenders(
                {
                    source,
                    firstLine,
                    ->
                    CompletableFuture<Rendering>().also { requests += (source to firstLine) to it }
                },
                {
                    rerenders++
                    renders.page()
                },
                fxQueue::addLast,
                cacheSize,
            )
        return renders
    }

    private fun runFx() {
        while (fxQueue.isNotEmpty()) fxQueue.removeFirst().run()
    }

    /** Completes the pending request of ([source], [firstLine]) with [rendering] (built with the engine's own types). */
    private fun arrive(
        source: String,
        firstLine: Int = 1,
        rendering: Rendering = drawn(source),
    ) {
        requests.last { it.first == source to firstLine && !it.second.isDone }.second.complete(rendering)
        runFx()
    }

    private fun drawn(name: String) =
        Rendering(listOf(Diagram.Drawn("<svg>$name</svg>", "<svg>$name dark</svg>")), false)

    private val requested get() = requests.map { it.first }

    @Test
    fun `peeking gives a kept rendering for its first line and requests nothing`() {
        val renders = renders()
        assertNull(renders.peek("A", 1))
        assertEquals(emptyList(), requested)

        assertNull(renders.get("A", 1))
        val rendering = drawn("A")
        requests.single().second.complete(rendering)
        runFx()

        assertSame(rendering, renders.peek("A", 1))
        assertNull(renders.peek("A", 2))
        assertNull(renders.peek("B", 1))
        assertEquals(listOf("A" to 1), requested)
    }

    @Test
    fun `a missing rendering is requested once while pending, on the FX thread when it arrives, then kept`() {
        val renders = renders()

        assertNull(renders.get("A", 1))
        assertNull(renders.get("A", 1))
        assertNull(renders.get("A", 3))
        assertEquals(listOf("A" to 1, "A" to 3), requested)

        val rendering = drawn("A")
        requests.first().second.complete(rendering)
        assertNull(renders.get("A", 1), "not before the FX thread has it")
        assertEquals(0, rerenders)
        runFx()

        assertEquals(1, rerenders)
        assertSame(rendering, renders.get("A", 1))
        assertEquals(listOf("A" to 1, "A" to 3), requested, "a kept rendering is not requested again")
    }

    @Test
    fun `a result rerenders only if the page asked for it since the last rerender began`() {
        val renders = renders()
        // Page 1 asks for A and B; once B is there, page 2 asks for B and C; once C is there, page 3 too.
        renders.get("A", 1)
        renders.get("B", 1)
        page = {
            get("B", 1)
            get("C", 1)
        }
        arrive("B")
        assertEquals(1, rerenders)
        arrive("C")
        assertEquals(2, rerenders)

        // A, asked for two pages ago, is no longer on the page: no pointless reload, but it is kept.
        arrive("A")
        assertEquals(2, rerenders)
        assertEquals(drawn("A"), renders.get("A", 1))
        assertEquals(listOf("A" to 1, "B" to 1, "C" to 1), requested)
    }

    @Test
    fun `after detach a result is neither kept nor rerendered`() {
        val renders = renders()
        renders.get("A", 1)

        renders.detach()
        arrive("A")

        assertEquals(0, rerenders)
        assertNull(renders.get("A", 1))
    }

    @Test
    fun `the cache never drops what the page asks for, so a page larger than the cache is drawn once`() {
        val renders = renders(cacheSize = 2)
        page = {
            get("A", 1)
            get("B", 1)
            get("C", 1)
        }
        renders.page()

        // As the engine would: each pending request answered in turn, more than the page could ever need.
        repeat(20) { requests.firstOrNull { !it.second.isDone }?.let { arrive(it.first.first) } }

        assertEquals(listOf("A" to 1, "B" to 1, "C" to 1), requested)
        assertEquals(listOf(drawn("A"), drawn("B"), drawn("C")), listOf("A", "B", "C").map { renders.get(it, 1) })
    }

    @Test
    fun `the cache keeps the most recently used renderings`() {
        val renders = renders(cacheSize = 2)
        renders.get("A", 1)
        renders.get("B", 1)
        arrive("A")
        arrive("B")
        // A used after B: B is the least recently used once C comes.
        renders.get("A", 1)
        renders.get("C", 1)
        arrive("C")

        assertEquals(drawn("A"), renders.get("A", 1))
        assertEquals(drawn("C"), renders.get("C", 1))
        assertNull(renders.get("B", 1))
        assertEquals(listOf("A" to 1, "B" to 1, "C" to 1, "B" to 1), requested, "B is requested again")
    }

    @Test
    fun `with the app's engine a source is drawn and handed over on the FX thread`() {
        val fx = LinkedBlockingQueue<Runnable>()
        val renders = DiagramRenders(PlantUml.shared, { rerenders++ }, fx::add)
        val source = "@startuml\nAlice -> Bob : hello\n@enduml\n"

        assertNull(renders.get(source, 1))
        checkNotNull(fx.poll(60, TimeUnit.SECONDS)) { "no result from the engine" }.run()

        assertEquals(1, rerenders)
        val diagram = renders.get(source, 1)?.diagrams?.single()
        assertTrue(diagram is Diagram.Drawn && "Alice" in diagram.lightSvg, "$diagram")
    }
}
