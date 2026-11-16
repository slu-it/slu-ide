package dev.sluide.java

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * When the mirror settles a job it is in doubt about (and only then), held statuses, a resync's doubt, and what a
 * restart forgets; [JavaImportsQueueTest] has the queue's rules.
 */
internal class JavaImportsSettleTest : JavaImportsFixture() {
    private val x by lazy { root("x") }
    private val y by lazy { root("y") }

    /** x imported, then removed and y added: the removal job is kept in doubt (b2.txt 50296: it was cancelled). */
    private fun doubtfulRemoval() {
        projects("file:$x/")
        add(x)
        begin("x")
        end("x")
        imports.onFolderRemoved(x)
        imports.onFolderAdded(y)
        status(0, 1)
        status(1, 0)
        projects("file:$x/", "file:$y/")
    }

    @Test
    fun `a status resets the silence the mirror waits for`() {
        doubtfulRemoval()
        tick(SETTLE.seconds - 1)
        val c = root("c")

        add(c)
        tick(1)

        assertEquals(ImportState.Importing(now), states[y])
        tick(SETTLE.seconds - 1)
        assertIs<ImportState.Done>(states[y])
    }

    @Test
    fun `nothing is settled while a notification waits for its status`() {
        doubtfulRemoval()
        imports.onFolderAdded(root("c"))

        tick(SETTLE.seconds)

        assertEquals(ImportState.Importing(now), states[y])
    }

    @Test
    fun `a settle ends the doubt, so a later job that waits is not settled`() {
        doubtfulRemoval()
        tick(SETTLE.seconds)
        assertIs<ImportState.Done>(states[y])
        val c = root("c")

        add(c)
        repeat(2) { tick(SETTLE.seconds) }

        assertIs<ImportState.Importing>(states[c])
    }

    @Test
    fun `a removal of a root whose add began is no doubt, a later job that waits is not settled`() {
        val a = root("a")
        val c = root("c")
        projects("file:$a/")
        add(a)
        begin("a")

        remove(a)
        add(c)
        end("a")
        repeat(2) { tick(SETTLE.seconds) }

        assertEquals(ImportState.Importing(now), states[c])
    }

    @Test
    fun `after a mismatch the queued roots are settled once jdtls is silent`() {
        val a = root("a")
        val b = root("b")
        projects("file:$a/")
        add(a)
        imports.onFolderAdded(b)

        status(0, 1)
        tick(SETTLE.seconds)

        assertIs<ImportState.Done>(states[a])
        assertIs<ImportState.Failed>(states[b])
        assertFalse(ticking)
    }

    @Test
    fun `a removal's status that comes before its notification lets what it held go on once the removal is sent`() {
        val a = root("a")
        val b = root("b")
        projects("file:$a/")
        add(a)
        add(b)

        status(0, 1)
        begin("a")
        end("a")
        imports.onFolderRemoved(b)

        assertIs<ImportState.Done>(states[a])
    }

    @Test
    fun `a status taken after it waited is not dropped later`() {
        val a = root("a")
        projects("file:$a/")
        status(1, 0)
        imports.onFolderAdded(a)

        tick(SETTLE.seconds)

        assertEquals(ImportState.Importing(now), states[a])
        assertEquals(emptyList(), warnings)
        begin("a")
        end("a")
        assertIs<ImportState.Done>(states[a])
    }

    @Test
    fun `a stop forgets the getAll to ask again`() {
        val a = root("a")
        add(a)
        begin("a")
        access.answers["java.project.getAll"] =
            { CompletableFuture.failedFuture(IllegalStateException("busy")) }
        end("a")
        imports.onStopped()
        val b = root("b")
        projects()
        add(b)
        begin("b")
        end("b")
        assertIs<ImportState.Failed>(states[b])
        add(root("c")) // Keeps the ticker running.

        tick(SETTLE.seconds)

        assertEquals(2, access.commands.count { it.first == "java.project.getAll" })
    }

    @Test
    fun `a new connection forgets a status that waited for its notification`() {
        status(0, 1)
        imports.onConnected()
        val b = root("b")
        projects("file:$b/")

        imports.onFolderAdded(b)
        tick(SETTLE.seconds) // The forgotten status's wait does not go on.
        status(1, 0)
        begin("b")
        end("b")

        assertIs<ImportState.Done>(states[b])
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `a stop forgets a status that waited for its notification`() {
        status(0, 1)
        imports.onStopped()
        val b = root("b")
        projects("file:$b/")

        imports.onFolderAdded(b)
        tick(SETTLE.seconds) // The forgotten status's wait does not go on.
        status(1, 0)
        begin("b")
        end("b")

        assertIs<ImportState.Done>(states[b])
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `the tokens of a stopped server end nothing of the next one`() {
        val a = root("a")
        add(a)
        begin("old")
        begin("stray")
        imports.onStopped()
        val b = root("b")
        projects()
        add(b)
        begin("b")
        end("b")
        assertIs<ImportState.Failed>(states[b])
        projects("file:$b/")

        end("old")
        end("stray")

        assertIs<ImportState.Failed>(states[b], "no getAll after a token of the old server")
    }

    @Test
    fun `a stop forgets the open tokens and the doubt`() {
        begin("build", "Building")
        doubtfulRemoval()
        imports.onStopped()

        val c = root("c")
        add(c)
        repeat(2) { tick(SETTLE.seconds) }
        assertEquals(ImportState.Importing(now), states[c], "no doubt after the stop")
        doubtfulRemoval()
        tick(SETTLE.seconds)

        assertIs<ImportState.Done>(states[y], "the old server's open token keeps nothing from settling")
    }

    @Test
    fun `an answer of getAll that comes after a stop or a close upgrades nothing`() {
        val answers = ArrayDeque<CompletableFuture<Any?>>()
        access.answers["java.project.getAll"] = { CompletableFuture<Any?>().also(answers::addLast) }
        val a = root("a")
        add(a)
        begin("a")
        end("a")
        imports.onStopped()
        val c = failedRoot("c", answers)

        answers.removeFirst().complete(listOf("file:$a/", "file:$c/"))
        assertIs<ImportState.Failed>(states[c], "the answer of the stopped server")
        add(root("d"))
        begin("d")
        end("d")
        imports.close()
        answers.removeFirst().complete(listOf("file:$c/"))

        assertIs<ImportState.Failed>(states[c], "the answer after closing")
    }

    /** Adds [name] and lets its import fail through an answer of getAll that lists nothing. */
    private fun failedRoot(
        name: String,
        answers: ArrayDeque<CompletableFuture<Any?>>,
    ): Path {
        val root = root(name)
        add(root)
        begin(name)
        end(name)
        answers.removeLast().complete(emptyList<String>())
        assertIs<ImportState.Failed>(states[root])
        return root
    }
}
