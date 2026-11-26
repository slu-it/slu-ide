package dev.sluide.plantuml

import dev.sluide.plantuml.engine.DotProcesses
import dev.sluide.testsupport.LogCapture
import net.sourceforge.plantuml.cli.GlobalConfig
import net.sourceforge.plantuml.cli.GlobalConfigKey
import net.sourceforge.plantuml.security.SecurityProfile
import net.sourceforge.plantuml.security.SecurityUtils
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PlantUmlTest {
    private val noGraphviz = GraphvizLocator({ null }, emptyList())

    /** A latch per source starting with "hang"; all are released at the end of each test. */
    private val latches = ConcurrentHashMap<String, CountDownLatch>()

    private fun latch(source: String) = latches.computeIfAbsent(source) { CountDownLatch(1) }

    private fun release(source: String) = latch(source).countDown()

    private val threads = Collections.synchronizedList(mutableListOf<Thread>())
    private val threadFactory =
        ThreadFactory { task ->
            Thread(task, "render-${threads.size + 1}").apply { isDaemon = true }.also(threads::add)
        }

    /** What the fake draw saw: the source and the thread's name, in order. */
    private val draws = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    @AfterTest
    fun releaseBlockedDraws() {
        latches.values.forEach(CountDownLatch::countDown)
    }

    private fun engine(
        timeout: Duration = 20.seconds,
        locator: GraphvizLocator = noGraphviz,
        dotKiller: (Path) -> DotProcesses.Kill = { DotProcesses.Kill {} },
        watchdog: ScheduledExecutorService = PlantUml.watchdogExecutor(),
        beforeTimeoutDelivered: (() -> Unit)? = null,
        draw: (String, () -> Boolean) -> Rendering = { source, _ -> fakeDraw(source) },
    ) = PlantUml(locator, timeout, threadFactory, { source, _, _, givenUp ->
        draws += source to Thread.currentThread().name
        draw(source, givenUp)
    }, dotKiller, watchdog, beforeTimeoutDelivered)

    private fun fakeDraw(source: String): Rendering {
        if (source.startsWith("hang")) latch(source).await()
        return Rendering(listOf(Diagram.Failed(source, null)), false)
    }

    private fun <T> CompletableFuture<T>.within(): T {
        awaitTrue { isDone }
        return get()
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "condition not met within 10 s")
            Thread.sleep(10)
        }
    }

    @Test
    fun `the shared engine is one engine`() {
        val shared = PlantUml.shared

        assertNotNull(shared)
        assertSame(shared, PlantUml.shared)
    }

    @Test
    fun `the engine runs PlantUML in its SANDBOX profile and waits at most 15 s for a dot that closed its output`() {
        PlantUml(noGraphviz).render("@startuml\nAlice -> Bob : hi\n@enduml\n").within()

        assertEquals(SecurityProfile.SANDBOX, SecurityUtils.getSecurityProfile())
        assertEquals(15_000L, GlobalConfig.getInstance().value(GlobalConfigKey.TIMEOUT_MS))
    }

    @Test
    fun `renders and timers run on daemon threads named after PlantUML`() {
        val render = PlantUml.renderThreads.newThread {}
        val watchdog = PlantUml.watchdogExecutor().threadFactory.newThread {}

        assertEquals(listOf(true, true), listOf(render.isDaemon, watchdog.isDaemon))
        assertEquals(listOf("PlantUML render", "PlantUML watchdog"), listOf(render.name, watchdog.name))
    }

    @Test
    fun `render draws the source with the caller's line numbers`() {
        val rendering = PlantUml(noGraphviz).render("@startuml\nAlice -> Bob : hi\nnot valid\n@enduml\n", 10).within()

        assertEquals(
            Rendering(listOf(Diagram.Failed("Syntax Error? (Assumed diagram type: sequence)", 12)), false),
            rendering,
        )
    }

    @Test
    fun `renders run one at a time, in call order, each completing its own future`() {
        val active = AtomicInteger()
        val most = AtomicInteger()
        val engine =
            engine { source, _ ->
                most.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                fakeDraw(source).also { active.decrementAndGet() }
            }

        val futures = listOf("hang first", "second", "third", "fourth").map { engine.render(it) }
        awaitTrue { draws.isNotEmpty() }
        // Nothing signals that a second draw did not start: give it a moment while the first one blocks.
        Thread.sleep(200)
        assertEquals(1, draws.size)
        release("hang first")

        assertEquals(
            listOf("hang first", "second", "third", "fourth"),
            futures.map { (it.within().diagrams.single() as Diagram.Failed).message },
        )
        assertEquals(listOf("hang first", "second", "third", "fourth"), draws.map { it.first })
        assertEquals(1, most.get())
    }

    @Test
    fun `a render past the timeout completes with the timeout and the queue goes on on a fresh thread`() {
        val engine = engine(timeout = 1.seconds)

        val hung = engine.render("hang")
        val next = engine.render("next")
        val last = engine.render("last")

        assertEquals(Rendering(listOf(Diagram.Failed("Drawing took longer than 1 s", null)), false), hung.within())
        assertEquals("next", (next.within().diagrams.single() as Diagram.Failed).message)
        assertEquals("last", (last.within().diagrams.single() as Diagram.Failed).message)
        assertEquals(listOf("hang" to "render-1", "next" to "render-2", "last" to "render-2"), draws.toList())
    }

    @Test
    fun `an abandoned render's thread takes no more renders`() {
        // The test fires hang1's timer itself; hang2's never fires within the 20 s timeout.
        val timers = HeldTimers()
        val engine = engine(watchdog = timers)
        val hung = engine.render("hang1")
        awaitTrue { timers.held.size == 1 }
        timers.held[0].run()
        hung.within()
        // render-2 is busy with hang2 while third waits in the queue.
        engine.render("hang2")
        val third = engine.render("third")
        awaitTrue { "hang2" to "render-2" in draws }

        release("hang1")
        threads[0].join(10_000)
        assertFalse(threads[0].isAlive)
        release("hang2")

        assertEquals("third", (third.within().diagrams.single() as Diagram.Failed).message)
        assertEquals(listOf("hang1" to "render-1"), draws.filter { it.second == "render-1" })
        timers.shutdownNow()
    }

    @Test
    fun `an abandoned render's cut-short result never reaches the caller, the timeout does`(
        @TempDir temp: Path,
    ) {
        // On the hung-dot path the killed export returns at once and the draw, told it was given up, returns early
        // with what it drew so far: here nothing. The watchdog is held between its locked step and its delivery until
        // that draw has returned and its thread ended, so the late result had every chance to be delivered first.
        val dot = executable(temp)
        val hookSaw = CompletableFuture<Boolean>()
        val engine =
            engine(
                timeout = 1.seconds,
                locator = GraphvizLocator({ dot.toString() }, emptyList()),
                dotKiller = { DotProcesses.Kill { release("hang") } },
                beforeTimeoutDelivered = {
                    threads[0].join(10_000)
                    hookSaw.complete(!threads[0].isAlive)
                },
            ) { source, givenUp ->
                fakeDraw(source)
                if (givenUp()) Rendering(emptyList(), false) else Rendering(listOf(Diagram.Failed(source, null)), false)
            }

        val rendering = engine.render("hang").within()

        // The race was forced: the watchdog delivered only after the abandoned render had ended.
        assertTrue(hookSaw.getNow(false))
        assertEquals(Rendering(listOf(Diagram.Failed("Drawing took longer than 1 s", null)), false), rendering)
    }

    @Test
    fun `the draw is told once its render was given up`() {
        val told = CompletableFuture<Pair<Boolean, Boolean>>()
        val engine =
            engine(timeout = 1.seconds) { source, givenUp ->
                val before = givenUp()
                fakeDraw(source).also { told.complete(before to givenUp()) }
            }

        engine.render("hang").within()
        release("hang")

        assertEquals(false to true, told.within())
    }

    @Test
    fun `the watchdog kills dot before a queued render can start one`(
        @TempDir temp: Path,
    ) {
        val dot = executable(temp)
        val killed = CompletableFuture<Pair<Path, Int>>()
        val engine =
            engine(
                timeout = 1.seconds,
                locator = GraphvizLocator({ dot.toString() }, emptyList()),
                dotKiller = { found -> DotProcesses.Kill { killed.complete(found to threads.size) } },
            )

        engine.render("hang")
        val next = engine.render("next")

        assertEquals("next", (next.within().diagrams.single() as Diagram.Failed).message)
        // Only the hung render's thread existed when dot was killed.
        assertEquals(dot to 1, killed.within())
    }

    @Test
    fun `a finished render leaves no timer behind`() {
        val watchdog = PlantUml.watchdogExecutor()
        val engine = engine(watchdog = watchdog)

        engine.render("quick").within()

        awaitTrue { threads[0].state == Thread.State.TERMINATED }
        assertEquals(0, watchdog.queue.size)
    }

    @Test
    fun `a timer that fires after its render finished does nothing`(
        @TempDir temp: Path,
    ) {
        val dot = executable(temp)
        val timers = HeldTimers()
        val killed = Collections.synchronizedList(mutableListOf<Path>())
        val engine =
            engine(
                locator = GraphvizLocator({ dot.toString() }, emptyList()),
                dotKiller = { found -> DotProcesses.Kill { killed.add(found) } },
                watchdog = timers,
            )

        val quick = engine.render("quick").within()
        // A timer that had already started when the render finished cannot be cancelled: it runs anyway.
        timers.held.single().run()
        val next = engine.render("next").within()

        assertEquals(
            listOf("quick", "next"),
            listOf(quick, next).map { (it.diagrams.single() as Diagram.Failed).message },
        )
        assertEquals(emptyList(), killed.toList())
        timers.shutdownNow()
    }

    /** Keeps the timers instead of running them: the test fires them itself. */
    private class HeldTimers : ScheduledThreadPoolExecutor(1, { Thread(it).apply { isDaemon = true } }) {
        val held: MutableList<Runnable> = Collections.synchronizedList(mutableListOf())

        override fun schedule(
            command: Runnable,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            held += command
            return super.schedule({}, 1, TimeUnit.DAYS)
        }
    }

    @Test
    fun `a draw that throws completes with a failed diagram, is logged, and later renders still run`() {
        // A real case: %load_json("<C4/nothing.json>") throws an UncheckedIOException out of PlantUML's parser.
        val broken = IllegalStateException("broken")
        val engine = engine { source, _ -> if (source == "boom") throw broken else fakeDraw(source) }

        val (failed, log) =
            LogCapture(PlantUml::class.java.name).use { log ->
                engine.render("boom").within() to log.lines.toList()
            }
        val next = engine.render("next").within()

        assertEquals(
            Rendering(listOf(Diagram.Failed("PlantUML failed: java.lang.IllegalStateException: broken", null)), false),
            failed,
        )
        assertEquals(listOf(Level.WARNING to "PlantUML failed"), log)
        assertEquals("next", (next.diagrams.single() as Diagram.Failed).message)
    }

    @Test
    fun `a PlantUML that cannot start gives a failed diagram per render, logged once, and the engine stays usable`() {
        // Measured in the app on a headless JDK: the first use of PlantUML threw UnsatisfiedLinkError from AWT in a
        // class initialiser; every later use of that class throws NoClassDefFoundError.
        val failures = ArrayDeque(listOf(UnsatisfiedLinkError("no libawt_xawt"), NoClassDefFoundError("FileFormat")))
        val engine = engine { source, _ -> failures.removeFirstOrNull()?.let { throw it } ?: fakeDraw(source) }

        val (results, log) =
            LogCapture(PlantUml::class.java.name).use { log ->
                listOf("first", "second").map { engine.render(it).within() } to log.lines.toList()
            }
        val later = engine.render("later").within()

        assertEquals(
            listOf(
                "PlantUML cannot start: java.lang.UnsatisfiedLinkError: no libawt_xawt",
                "PlantUML cannot start: java.lang.NoClassDefFoundError: FileFormat",
            ).map { Rendering(listOf(Diagram.Failed(it, null)), false) },
            results,
        )
        assertEquals(listOf(Level.WARNING to "PlantUML cannot start"), log)
        assertEquals("later", (later.diagrams.single() as Diagram.Failed).message)
    }

    @Test
    fun `the draw gets the source, the first line and the located dot`(
        @TempDir temp: Path,
    ) {
        val dot = executable(temp)
        val engine =
            PlantUml(
                GraphvizLocator(
                    { dot.toString() },
                    emptyList(),
                ),
                20.seconds,
                threadFactory,
                { source, line, d, _ ->
                    Rendering(listOf(Diagram.Failed("$source $d", line)), false)
                },
            )

        val rendering = engine.render("text", 42).within()

        assertEquals(Rendering(listOf(Diagram.Failed("text $dot", 42)), false), rendering)
    }

    private fun executable(temp: Path): Path =
        // It answers `dot -V` as Graphviz does, which the locator checks.
        Files.writeString(temp.resolve("dot"), "#!/bin/sh\necho 'dot - graphviz version 2.43.0 (0)' >&2\n").also {
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
        }
}
