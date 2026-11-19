package dev.sluide.languageservers.process

import dev.sluide.languageservers.awaitTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The list of server processes that the JVM's shutdown hook kills: a server must not outlive Thoreau (ADR 0142). */
class LiveServerProcessesTest {
    private val processes = mutableListOf<Process>()

    @AfterEach
    fun cleanUp() {
        processes.forEach {
            LiveServerProcesses.remove(it.toHandle())
            destroyTree(it.toHandle())
        }
    }

    private fun listed(script: String = "sleep 30"): Process =
        ProcessBuilder("/bin/sh", "-c", script).start().also {
            processes += it
            LiveServerProcesses.add(it.toHandle())
        }

    /** Forgets the installed hook, as in a fresh JVM: the object keeps it for the whole test run otherwise. */
    private fun forgetHook() {
        LiveServerProcesses.hookThread()?.let(Runtime.getRuntime()::removeShutdownHook)
        LiveServerProcesses::class.java
            .getDeclaredField("hook")
            .apply { isAccessible = true }
            .set(null, null)
    }

    @Test
    fun `the first process installs one shutdown hook that kills what is listed`() {
        forgetHook()
        assertNull(LiveServerProcesses.hookThread())
        val first = listed()
        val hook = assertNotNull(LiveServerProcesses.hookThread())
        val second = listed()

        assertSame(hook, LiveServerProcesses.hookThread(), "installed once")
        assertEquals("sluide-language-servers-shutdown", hook.name)
        assertTrue(Runtime.getRuntime().removeShutdownHook(hook), "the JVM runs it at exit")
        // Put it back for the rest of this JVM's tests, then do what the JVM would at exit.
        Runtime.getRuntime().addShutdownHook(hook)
        hook.run()

        awaitTrue("both killed") { !first.isAlive && !second.isAlive }
    }

    @Test
    fun `destroying all kills every listed process with its children and unlists them at once`() {
        val one = listed("sleep 30 & sleep 30")
        val two = listed()
        awaitTrue("child running") { one.descendants().count() == 2L }
        val children = one.descendants().toList()

        LiveServerProcesses.destroyAll()

        assertFalse(one.toHandle() in LiveServerProcesses.all() || two.toHandle() in LiveServerProcesses.all())
        awaitTrue("all ended") { !one.isAlive && !two.isAlive && children.none { it.isAlive } }
    }

    @Test
    fun `a removed process is no longer listed and survives destroying all`() {
        val kept = listed()
        val removed = listed()

        LiveServerProcesses.remove(removed.toHandle())
        assertTrue(removed.toHandle() !in LiveServerProcesses.all())
        LiveServerProcesses.destroyAll()

        awaitTrue("listed one ended") { !kept.isAlive }
        assertTrue(removed.isAlive)
    }
}
