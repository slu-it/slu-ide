package dev.sluide.projecttrust

import dev.sluide.backgroundtasks.BackgroundTasks
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectTrustTest {
    @TempDir
    lateinit var dir: Path

    private class FakeCheck(
        override val id: String,
        var findings: List<TrustFinding>? = emptyList(),
        val fails: Boolean = false,
    ) : ProjectTrustCheck {
        override val name = id.replaceFirstChar(Char::uppercase)
        var runs = 0

        override fun check(folder: Path): List<TrustFinding>? {
            runs++
            if (fails) error("broken")
            return findings
        }
    }

    private val opened = mutableListOf<ProjectTrust>()

    private val folder by lazy { dir.resolve("project").createDirectories() }

    private fun store() = TrustedProjects(dir.resolve("config/project-trust.properties"))

    /** A trust of [folder] with [checks] whose first checks have run. */
    private fun opened(vararg checks: ProjectTrustCheck): ProjectTrust {
        val trust = ProjectTrust(checks.toList(), store(), BackgroundTasks.forTests(), Runnable::run)
        opened += trust
        trust.open(folder)
        trust.awaitAllows(checks.first().id)
        return trust
    }

    /** The user's answer about what is shown. */
    private fun ProjectTrust.answer(
        trusted: Boolean,
        remember: Boolean = true,
    ) = decide(state.get(), trusted, remember)

    @AfterEach
    fun close() = opened.forEach { it.close() }

    private val hook = TrustFinding("hook pre-commit", "runs on commit", "hook\u0000pre-commit\u0000abc")

    @Test
    fun `a folder whose checks find nothing is trusted without asking and without storing`() {
        val trust = opened(FakeCheck("git"), FakeCheck("build"))

        assertIs<ProjectTrustState.Trusted>(trust.state.get())
        assertTrue(trust.allows("git"))
        assertFalse(store().file.exists())
    }

    @Test
    fun `findings ask, and only the tools that found something stay off`() {
        val trust = opened(FakeCheck("git", listOf(hook)), FakeCheck("build"))

        val state = assertIs<ProjectTrustState.Ask>(trust.state.get())
        assertFalse(state.changed)
        assertEquals(listOf("git"), state.flagged.map { it.id })
        assertEquals(listOf("Git", "Build"), state.results.map { it.name })
        assertFalse(trust.allows("git"))
        assertTrue(trust.allows("build"))
        assertTrue(trust.allows("tool-without-check"))
    }

    @Test
    fun `a trusted decision is stored and applied when the folder is opened again`() {
        opened(FakeCheck("git", listOf(hook))).answer(true)

        val again = opened(FakeCheck("git", listOf(hook)))

        assertIs<ProjectTrustState.Trusted>(again.state.get())
        assertTrue(again.allows("git"))
    }

    @Test
    fun `an untrusted decision is stored too and keeps the tool off`() {
        val trust = opened(FakeCheck("git", listOf(hook)))
        trust.answer(false)
        assertIs<ProjectTrustState.Untrusted>(trust.state.get())
        assertFalse(trust.allows("git"))

        assertIs<ProjectTrustState.Untrusted>(opened(FakeCheck("git", listOf(hook))).state.get())
    }

    @Test
    fun `a decision not to remember lasts only while the folder is open`() {
        val trust = opened(FakeCheck("git", listOf(hook)))
        trust.answer(false, remember = false)
        assertIs<ProjectTrustState.Untrusted>(trust.state.get())

        assertIs<ProjectTrustState.Ask>(opened(FakeCheck("git", listOf(hook))).state.get())
    }

    @Test
    fun `other findings than those decided about ask again`() {
        opened(FakeCheck("git", listOf(hook))).answer(true)

        val other = TrustFinding("core.fsmonitor = ./watch.sh", "runs on every git status")
        assertIs<ProjectTrustState.Ask>(opened(FakeCheck("git", listOf(hook, other))).state.get())
    }

    @Test
    fun `a recheck with new findings after a decision asks again as a change`() {
        val check = FakeCheck("git", listOf(hook))
        val trust = opened(check)
        trust.answer(true)

        assertTrue(trust.recheck("git"))
        assertIs<ProjectTrustState.Trusted>(trust.state.get())

        check.findings = listOf(hook, TrustFinding("filter.x.clean = ./x"))
        assertFalse(trust.recheck("git"))
        assertTrue(assertIs<ProjectTrustState.Ask>(trust.state.get()).changed)
        assertEquals(3, check.runs)
    }

    @Test
    fun `a recheck that finds nothing any more trusts without asking`() {
        val check = FakeCheck("git", listOf(hook))
        val trust = opened(check)

        check.findings = emptyList()

        assertTrue(trust.recheck("git"))
        assertIs<ProjectTrustState.Trusted>(trust.state.get())
    }

    @Test
    fun `a check that fails keeps its tool off, and trusting it is never stored`() {
        val trust = opened(FakeCheck("git", fails = true), FakeCheck("build"))

        val state = assertIs<ProjectTrustState.Ask>(trust.state.get())
        assertEquals(null, state.flagged.single().findings)
        assertFalse(trust.allows("git"))

        trust.answer(true)
        assertTrue(trust.allows("git"))
        assertFalse(store().file.exists())
    }

    @Test
    fun `closing the folder keeps every checked tool off`() {
        val trust = opened(FakeCheck("git"))

        trust.open(null)

        assertEquals(ProjectTrustState.Closed, trust.state.get())
        assertFalse(trust.allows("git"))
        assertFalse(trust.awaitAllows("git"))
        assertFalse(trust.recheck("git"))
    }

    @Test
    fun `an answer about findings that changed since they were shown is ignored`() {
        val check = FakeCheck("git", listOf(hook))
        val trust = opened(check)
        val shown = trust.state.get()

        check.findings = listOf(hook, TrustFinding("filter.x.clean = ./x"))
        trust.recheck("git")
        assertFalse(trust.decide(shown, trusted = true))

        val state = assertIs<ProjectTrustState.Ask>(trust.state.get())
        assertEquals(2, assertNotNull(state.flagged.single().findings).size)
        assertFalse(store().file.exists())
    }

    @Test
    fun `findings a recheck finds where there were none are a change`() {
        val check = FakeCheck("git")
        val trust = opened(check)

        check.findings = listOf(hook)

        assertFalse(trust.recheck("git"))
        assertTrue(assertIs<ProjectTrustState.Ask>(trust.state.get()).changed)
    }

    @Test
    fun `a recheck while still asking keeps the question one of opening`() {
        val check = FakeCheck("git", listOf(hook))
        val trust = opened(check)

        check.findings = listOf(hook, TrustFinding("filter.x.clean = ./x"))
        trust.recheck("git")

        assertFalse(assertIs<ProjectTrustState.Ask>(trust.state.get()).changed)
    }

    @Test
    fun `every answer while the folder is open is kept, not only the last`() {
        val check = FakeCheck("git", listOf(hook))
        val trust = opened(check)
        trust.answer(true, remember = false)

        check.findings = null
        trust.recheck("git")
        trust.answer(true, remember = false)
        check.findings = listOf(hook)

        assertTrue(trust.recheck("git"))
        assertIs<ProjectTrustState.Trusted>(trust.state.get())
    }

    @Test
    fun `results of a folder replaced while its checks run are dropped`() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val blocking =
            object : ProjectTrustCheck {
                override val id = "git"
                override val name = "Git"

                override fun check(folder: Path): List<TrustFinding>? {
                    if (folder.name == "first") {
                        started.countDown()
                        try {
                            Thread.sleep(10_000)
                        } catch (e: InterruptedException) {
                            interrupted.countDown()
                            throw e
                        }
                    }
                    return listOf(hook)
                }
            }
        val trust = ProjectTrust(listOf(blocking), store(), BackgroundTasks.forTests(), Runnable::run).also(opened::add)
        val first = dir.resolve("first").createDirectories()
        trust.open(first)
        started.await()
        assertEquals(ProjectTrustState.Checking, trust.state.get())
        assertFalse(trust.decide(trust.state.get(), trusted = true))
        val waiting = Executors.newSingleThreadExecutor().submit(Callable { trust.awaitAllows("git") })

        trust.open(folder)

        assertFalse(waiting.get(5, TimeUnit.SECONDS))
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the first folder's check is cancelled")
        assertTrue(trust.awaitAllows("other"))
        val state = assertIs<ProjectTrustState.Ask>(trust.state.get())
        assertEquals(listOf(hook), state.flagged.single().findings)
        assertFalse(store().file.exists())
    }

    @Test
    fun `closing while the checks run releases the tools waiting for them`() {
        val started = CountDownLatch(1)
        val blocking =
            object : ProjectTrustCheck {
                override val id = "git"
                override val name = "Git"

                override fun check(folder: Path): List<TrustFinding>? {
                    started.countDown()
                    Thread.sleep(10_000)
                    return emptyList()
                }
            }
        val trust = ProjectTrust(listOf(blocking), store(), BackgroundTasks.forTests(), Runnable::run).also(opened::add)
        trust.open(folder)
        started.await()
        val waiting = Executors.newSingleThreadExecutor().submit(Callable { trust.awaitAllows("git") })

        trust.close()

        assertFalse(waiting.get(5, TimeUnit.SECONDS))
        trust.open(folder)
        assertFalse(trust.awaitAllows("git"))
    }

    /** A check that finds nothing once [release] is counted down; [started] once it runs. */
    private class GatedCheck : ProjectTrustCheck {
        override val id = "git"
        override val name = "Git"
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun check(folder: Path): List<TrustFinding> {
            started.countDown()
            release.await()
            return emptyList()
        }
    }

    /** A trust of [folder] whose [check] runs until released; [timeout] is how long it is waited for. */
    private fun gated(
        check: GatedCheck,
        timeout: Duration = Duration.ofSeconds(30),
    ): ProjectTrust =
        ProjectTrust(listOf(check), store(), BackgroundTasks.forTests(), Runnable::run).also {
            it.checksTimeout = timeout
            opened += it
            it.open(folder)
            check.started.await()
        }

    @Test
    fun `a cancellable wait returns once the checks have run`() {
        val check = GatedCheck()
        val trust = gated(check)
        val waiting = Executors.newSingleThreadExecutor().submit(Callable { trust.awaitAllows("git") { false } })

        check.release.countDown()

        assertTrue(waiting.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a cancellable wait stops soon after it is cancelled`() {
        val check = GatedCheck()
        val trust = gated(check)
        val cancelled = AtomicBoolean(false)
        val asked = CountDownLatch(2)
        val waiting =
            Executors.newSingleThreadExecutor().submit(
                Callable {
                    trust.awaitAllows("git") {
                        asked.countDown()
                        cancelled.get()
                    }
                },
            )
        assertTrue(asked.await(5, TimeUnit.SECONDS), "waits, asking whether it was cancelled")

        val start = System.nanoTime()
        cancelled.set(true)

        assertFalse(waiting.get(5, TimeUnit.SECONDS))
        val took = Duration.ofNanos(System.nanoTime() - start)
        assertTrue(took < Duration.ofSeconds(2), "far below the 30 s limit; took $took")
        check.release.countDown()
    }

    @Test
    fun `waiting for the checks ends at the time limit`() {
        val check = GatedCheck()
        val trust = gated(check, timeout = Duration.ofMillis(250))

        val start = System.nanoTime()
        assertFalse(trust.awaitAllows("git") { false })
        assertFalse(trust.awaitAllows("git"))

        val took = Duration.ofNanos(System.nanoTime() - start)
        assertTrue(took >= Duration.ofMillis(500) && took < Duration.ofSeconds(5), "took $took")
        check.release.countDown()
    }

    @Test
    fun `an answer while nothing was found changes nothing`() {
        val trust = opened(FakeCheck("git"))

        assertFalse(trust.decide(trust.state.get(), trusted = false))

        assertIs<ProjectTrustState.Trusted>(trust.state.get())
        assertFalse(store().file.exists())
    }

    @Test
    fun `the first checks and every recheck are a background task`() {
        val lines = mutableListOf<String>()
        val tasks = BackgroundTasks.forTests { synchronized(lines) { lines += it.replace(Regex("task#\\d+"), "task") } }
        val trust = ProjectTrust(listOf(FakeCheck("git")), store(), tasks, Runnable::run).also(opened::add)
        trust.open(folder)
        assertTrue(trust.awaitAllows("git"))
        assertTrue(trust.recheck("git"))

        val recorded = synchronized(lines) { lines.toList() }
        assertEquals(4, recorded.size, recorded.toString())
        assertEquals("task started: Checking project trust", recorded[0])
        assertTrue(recorded[1].startsWith("task finished in "), recorded[1])
        assertEquals("task started: Checking project trust", recorded[2])
        assertTrue(recorded[3].endsWith(": Checking project trust"), recorded[3])
    }

    @Test
    fun `an interrupted check ends its task as cancelled and keeps the interrupt`() {
        val lines = mutableListOf<String>()
        val tasks = BackgroundTasks.forTests { synchronized(lines) { lines += it.replace(Regex("task#\\d+"), "task") } }
        val interrupted =
            object : ProjectTrustCheck {
                override val id = "git"
                override val name = "Git"
                var runs = 0

                override fun check(folder: Path): List<TrustFinding> {
                    if (++runs > 1) throw InterruptedException("stopped")
                    return emptyList()
                }
            }
        val trust = ProjectTrust(listOf(interrupted), store(), tasks, Runnable::run).also(opened::add)
        trust.open(folder)
        assertTrue(trust.awaitAllows("git"))

        try {
            trust.recheck("git")
            assertTrue(Thread.currentThread().isInterrupted, "kept for the caller")
        } finally {
            Thread.interrupted()
        }

        val last = synchronized(lines) { lines.last() }
        assertTrue(last.startsWith("task cancelled after "), last)
        assertTrue(last.endsWith(": Checking project trust"), last)
    }

    @Test
    fun `checks need distinct ids`() {
        assertFailsWith<IllegalArgumentException> {
            ProjectTrust(
                listOf(FakeCheck("git"), FakeCheck("git")),
                store(),
                BackgroundTasks.forTests(),
            ) {}
        }
    }

    @Test
    fun `the fingerprint does not depend on order, and checks that found nothing add nothing`() {
        val a = TrustFinding("a")
        val b = TrustFinding("b")
        val git = TrustCheckResult("git", "Git", listOf(a, b))

        assertEquals(fingerprint(listOf(git)), fingerprint(listOf(git.copy(findings = listOf(b, a)))))
        assertEquals(
            fingerprint(listOf(git)),
            fingerprint(listOf(git, TrustCheckResult("build", "Build", emptyList()))),
        )
        assertNotEquals(fingerprint(listOf(git)), fingerprint(listOf(git.copy(id = "other"))))
        assertNotEquals(
            fingerprint(listOf(git)),
            fingerprint(listOf(git, TrustCheckResult("build", "Build", null))),
        )
        assertNotEquals(
            fingerprint(listOf(git)),
            fingerprint(listOf(git.copy(findings = listOf(TrustFinding("a\ngit\u0000b"))))),
            "one finding cannot pass for two",
        )
    }

    @Test
    fun `waiting for the checks is refused on the JavaFX thread`() {
        val trust = opened(FakeCheck("tool"))
        val failures = mutableListOf<Throwable>()
        val thread =
            Thread {
                for (call in listOf({ trust.awaitAllows("tool") }, { trust.recheck("tool") })) {
                    runCatching { call() }.onFailure(failures::add)
                }
            }
        thread.name = "JavaFX Application Thread"
        thread.start()
        thread.join(5_000)

        assertEquals(2, failures.size)
        assertTrue(failures.all { it is IllegalStateException })
    }
}
