package dev.sluide.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadSamplerTest {
    /** Gives the scripted values in order: [cpu] per call of `cpuTimes` (the last repeats), [dumps] per `dump`. */
    private class FakeSource(
        val cpu: ArrayDeque<Map<Long, Long>>,
        val dumps: ArrayDeque<List<ThreadSnapshot>>,
        val process: ArrayDeque<Long?> = ArrayDeque(listOf(1_000_000_000L, 1_500_000_000L)),
        val gc: ArrayDeque<List<GcCount>> =
            ArrayDeque(listOf(listOf(GcCount("Young", 3, 20)), listOf(GcCount("Young", 5, 32)))),
        val locked: List<Long> = emptyList(),
    ) : ThreadSource {
        var depths = mutableListOf<Int>()

        override fun cpuTimes() = if (cpu.size > 1) cpu.removeFirst() else cpu.first()

        override fun dump(depth: Int): List<ThreadSnapshot> {
            depths += depth
            return dumps.removeFirst()
        }

        override fun processCpuNanos() = process.removeFirst()

        override fun gcCounts() = gc.removeFirst()

        override fun heap() = Heap(100, 200, -1)

        override fun deadlocked() = locked

        override fun processors() = 4
    }

    private fun frame(method: String) = StackTraceElement("dev.sluide.Work", method, "Work.kt", 1)

    private fun stack(vararg methods: String) = methods.map(::frame)

    private fun thread(
        id: Long,
        name: String,
        stack: List<StackTraceElement>,
        state: Thread.State = Thread.State.RUNNABLE,
    ) = ThreadSnapshot(id, name, state, false, stack)

    /** A clock that advances one second per call, so the window (begin to end) is one second. */
    private fun clock(): () -> Long {
        var now = 0L
        return { now.also { now += 1_000_000_000 } }
    }

    @Test
    fun `ranks threads by the CPU they used in the window`() {
        val source =
            FakeSource(
                cpu =
                    ArrayDeque(
                        listOf(
                            mapOf(1L to 5_000_000_000, 2L to 0L, 3L to 100L, 99L to 0L),
                            // At the sample: 3 has used 400 ms, then it ends.
                            mapOf(1L to 5_050_000_000, 2L to 500_000_000, 3L to 400_000_100),
                            // 4 started in the window.
                            mapOf(1L to 5_100_000_000, 2L to 900_000_000, 4L to 300_000_000, 99L to 50_000_000),
                        ),
                    ),
                process = ArrayDeque(listOf(1_000_000_000L, 3_000_000_000L)),
                dumps =
                    ArrayDeque(
                        listOf(
                            listOf(thread(1, "old", stack("a")), thread(3, "gone", stack("g"))),
                            listOf(
                                thread(1, "old", stack("a"), Thread.State.WAITING),
                                thread(2, "busy", stack("b")),
                                thread(4, "new", stack("n")),
                            ),
                        ),
                    ),
            )
        val sampler = ThreadSampler(source, excluded = 99, clock = clock())

        sampler.begin()
        sampler.sample()
        val result = sampler.end()

        assertEquals(
            listOf("busy" to 900_000_000L, "gone" to 400_000_000L, "new" to 300_000_000L, "old" to 100_000_000L),
            result.threads.map { it.name to it.cpuNanos },
        )
        assertEquals(Thread.State.WAITING, result.threads.single { it.name == "old" }.state)
        assertNull(result.threads.single { it.name == "gone" }.state, "ended")
        assertEquals(50_000_000L, result.samplerCpuNanos)
        // 2000 ms of the process, 1700 ms of the threads, 50 ms of the sampler.
        assertEquals(250_000_000L, result.unattributedNanos)
        assertEquals(1_000_000_000, result.wallNanos)
        assertEquals(2, result.samples)
        assertEquals(2_000_000_000L, result.processCpuNanos)
        assertEquals(listOf(GcDelta("Young", 2, 12)), result.gc)
        assertEquals(listOf(DUMP_DEPTH, DUMP_DEPTH), source.depths)
    }

    @Test
    fun `lists the most frequent stacks of the busiest threads and always the JavaFX thread`() {
        val busyStacks = listOf(stack("x"), stack("y"), stack("x"), stack("z"), stack("x"), stack("w"), stack("y"))
        val threads = (1L..7L).map { it to "worker-$it" }
        val dumps =
            busyStacks.map { busy ->
                threads.map { (id, name) -> thread(id, name, if (id == 1L) busy else stack("idle")) } +
                    thread(50, FX_THREAD, stack("pulse"))
            }
        val source =
            FakeSource(
                cpu =
                    ArrayDeque(
                        listOf(
                            emptyMap(),
                            // worker-1 busiest, the JavaFX thread idle: not among the five on its own.
                            threads.associate { (id, _) -> id to (100L - id) } + (50L to 1L) + (99L to 1_000L),
                        ),
                    ),
                dumps = ArrayDeque(dumps),
            )
        val sampler = ThreadSampler(source, excluded = 99, clock = clock())

        sampler.begin()
        repeat(busyStacks.size - 1) { sampler.sample() }
        val result = sampler.end()

        assertEquals(
            listOf("worker-1", "worker-2", "worker-3", "worker-4", "worker-5", FX_THREAD),
            result.hot.map { it.name },
        )
        val busy = result.hot.first()
        assertEquals(7, busy.samples)
        assertEquals(listOf(stack("x") to 3, stack("y") to 2), busy.stacks.take(2).map { it.frames to it.count })
        assertEquals(STACKS_PER_THREAD, busy.stacks.size)
        assertEquals(1L, result.hot.last().cpuNanos)
        assertEquals(
            listOf(stack("pulse") to 7),
            result.hot
                .last()
                .stacks
                .map { it.frames to it.count },
        )
    }

    @Test
    fun `tells running or blocked samples from native waits and nested event loops`() {
        val runLoop = StackTraceElement("com.sun.glass.ui.gtk.GtkApplication", "_runLoop", null, -2)
        val nested = StackTraceElement("com.sun.glass.ui.gtk.GtkApplication", "_enterNestedEventLoopImpl", null, -2)
        val epoll = StackTraceElement("sun.nio.ch.EPoll", "wait", null, -2)
        val fxStacks = listOf(listOf(runLoop), listOf(nested, frame("showAndWait")), listOf(runLoop))
        val source =
            FakeSource(
                cpu = ArrayDeque(listOf(emptyMap(), mapOf(1L to 10L, 2L to 10L, 50L to 1L))),
                dumps =
                    ArrayDeque(
                        listOf(Thread.State.RUNNABLE, Thread.State.BLOCKED, Thread.State.WAITING).mapIndexed {
                            index,
                            state,
                            ->
                            listOf(
                                thread(1, "worker", stack("work"), state),
                                thread(2, "selector", listOf(epoll)),
                                thread(50, FX_THREAD, fxStacks[index] + stack("launch")),
                            )
                        },
                    ),
            )
        val sampler = ThreadSampler(source, excluded = 99, clock = clock())

        sampler.begin()
        repeat(2) { sampler.sample() }
        val result = sampler.end()

        val worker = result.hot.single { it.name == "worker" }
        assertEquals(3, worker.samples)
        assertEquals(2, worker.active)
        assertEquals(listOf(stack("work") to 2), worker.stacks.map { it.frames to it.count })
        assertEquals(0, worker.nativeWaits + worker.nestedLoops)
        val selector = result.hot.single { it.name == "selector" }
        assertEquals(listOf(0, 3), listOf(selector.active, selector.nativeWaits))
        assertEquals(listOf(listOf(epoll) to 3), selector.waitStacks.map { it.frames to it.count })
        val fx = result.hot.single { it.name == FX_THREAD }
        assertEquals(listOf(0, 2, 1), listOf(fx.active, fx.nativeWaits, fx.nestedLoops))
        assertTrue(fx.stacks.isEmpty())
        assertEquals(
            listOf(listOf(runLoop) + stack("launch") to 2, listOf(nested, frame("showAndWait")) + stack("launch") to 1),
            fx.waitStacks.map { it.frames to it.count },
        )
    }

    @Test
    fun `leaves out the sampling thread and cuts hot stacks`() {
        val deep = (1..HOT_STACK_DEPTH + 5).map { frame("m$it") }
        val source =
            FakeSource(
                cpu = ArrayDeque(listOf(emptyMap(), mapOf(1L to 10L, 99L to 1_000L))),
                dumps = ArrayDeque(listOf(listOf(thread(1, "deep", deep), thread(99, "sampler", stack("s"))))),
                locked = listOf(1L),
            )
        val sampler = ThreadSampler(source, excluded = 99, clock = clock())

        sampler.begin()
        val result = sampler.end()

        assertEquals(listOf("deep"), result.threads.map { it.name })
        assertEquals(listOf("deep"), result.finalDump.map { it.name })
        assertEquals(listOf("deep"), result.hot.map { it.name })
        val hot =
            result.hot
                .single()
                .stacks
                .single()
        assertEquals(HOT_STACK_DEPTH, hot.frames.size)
        assertTrue(hot.cut)
        assertEquals(listOf("deep"), result.deadlocked)
    }

    @Test
    fun `without CPU times it ranks no thread`() {
        val source =
            FakeSource(
                cpu = ArrayDeque(listOf(emptyMap(), emptyMap())),
                dumps = ArrayDeque(listOf(listOf(thread(1, "a", stack("a"))))),
                process = ArrayDeque(listOf(null, null)),
            )
        val sampler = ThreadSampler(source, excluded = 99, clock = clock())

        sampler.begin()
        val result = sampler.end()

        assertNull(result.processCpuNanos)
        assertTrue(result.threads.isEmpty())
        assertTrue(result.hot.isEmpty())
    }
}
