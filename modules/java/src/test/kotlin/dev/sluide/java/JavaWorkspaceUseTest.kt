package dev.sluide.java

import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaWorkspaceUseTest {
    private val use = JavaWorkspaceUse { it.run() }
    private val folder = Path.of("/home/u/.sluide/jdtls/workspaces/app-0123456789ab")

    @Test
    fun `a folder is in use from its acquire to its close`() {
        assertFalse(folder in use.inUse)

        val held = use.acquire(folder)
        assertTrue(folder in use.inUse)

        held.close()
        assertFalse(folder in use.inUse)
    }

    @Test
    fun `isInUse tells at once from any thread, before the set follows, for any spelling of the folder`() {
        val dispatched = mutableListOf<Runnable>()
        val deferred = JavaWorkspaceUse { dispatched += it }
        assertFalse(deferred.isInUse(folder))

        val held = deferred.acquire(Path.of("/home/u/.sluide/jdtls/workspaces/x/../app-0123456789ab"))
        assertTrue(deferred.isInUse(folder), "the set has not been told yet")
        assertFalse(deferred.isInUse(Path.of("/home/u/.sluide/jdtls/workspaces/other-111111111111")))

        held.close()
        assertFalse(deferred.isInUse(folder))
    }

    @Test
    fun `two acquires need two closes`() {
        val first = use.acquire(folder)
        val second = use.acquire(folder)

        first.close()
        assertTrue(folder in use.inUse, "the second still holds it")

        second.close()
        assertFalse(folder in use.inUse)
    }

    @Test
    fun `closing the same acquire twice releases only that one`() {
        val first = use.acquire(folder)
        val second = use.acquire(folder)

        first.close()
        first.close()

        assertTrue(folder in use.inUse)
        second.close()
        assertFalse(folder in use.inUse)
    }

    @Test
    fun `folders are told apart and a spelling with dots is the same folder`() {
        val other = Path.of("/home/u/.sluide/jdtls/workspaces/lib-ba9876543210")
        val held = use.acquire(folder)
        val heldOther = use.acquire(other)
        val dotted = use.acquire(Path.of("/home/u/.sluide/jdtls/workspaces/x/../app-0123456789ab"))

        held.close()
        assertTrue(folder in use.inUse, "the dotted spelling holds the same folder")
        dotted.close()
        assertFalse(folder in use.inUse)
        assertTrue(other in use.inUse)
        heldOther.close()
    }

    @Test
    fun `the set is changed through the dispatcher and cannot be changed by the reader`() {
        val dispatched = mutableListOf<Runnable>()
        val deferred = JavaWorkspaceUse { dispatched += it }

        val held = deferred.acquire(folder)
        assertFalse(folder in deferred.inUse, "not before the dispatcher ran the change")
        dispatched.removeFirst().run()
        assertTrue(folder in deferred.inUse)

        held.close()
        dispatched.removeFirst().run()
        assertFalse(folder in deferred.inUse)
        assertTrue(runCatching { deferred.inUse.add(folder) }.isFailure)
    }

    @Test
    fun `acquires from many threads are all counted`() {
        val fx = Executors.newSingleThreadExecutor()
        val threaded = JavaWorkspaceUse { fx.execute(it) }
        val pool = Executors.newFixedThreadPool(8)
        val held = (1..200).map { pool.submit<AutoCloseable> { threaded.acquire(folder) } }.map { it.get() }

        held.drop(1).forEach { it.close() }
        fx.submit {}.get() // everything dispatched before it has run
        assertTrue(folder in threaded.inUse, "one is still held")
        held.first().close()
        fx.submit {}.get()
        assertFalse(folder in threaded.inUse)
        pool.shutdown()
        fx.shutdown()
        assertEquals(true, pool.awaitTermination(5, TimeUnit.SECONDS))
    }
}
