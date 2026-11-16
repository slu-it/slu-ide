package dev.sluide.java

import com.google.gson.JsonParser
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules of the mirror of jdtls's update jobs; [JavaImportsTraceTest] has the measured orders. */
internal class JavaImportsQueueTest : JavaImportsFixture() {
    @Test
    fun `the tokens bind to the announced roots in order, whatever tokens run in between`() {
        val a = root("a")
        val b = root("b")
        add(a)
        add(b)
        projects(a.toUri().toString(), b.toUri().toString())
        begin("jdk", "LookupJDKToolchainsJob")
        begin("watch", "Register Watchers")
        begin("t1")
        end("jdk")
        end("watch", "done")
        assertEquals(emptyList(), access.commands)
        end("t1")
        assertIs<ImportState.Done>(states[a])
        assertIs<ImportState.Importing>(states[b])
        begin("search", "Searching...")
        begin("t2")
        end("search")
        end("t2")

        assertIs<ImportState.Done>(states[b])
        assertEquals(2, access.commands.count { it.first == "java.project.getAll" })
    }

    @Test
    fun `a token of the root's earlier announcement does not finish its new import`() {
        val a = root("a")
        add(a)
        begin("old")
        remove(a)
        add(a)
        projects("file:$a/")

        end("old")

        assertIs<ImportState.Importing>(states[a])
        begin("new")
        end("new")
        assertIs<ImportState.Done>(states[a])
    }

    @Test
    fun `a begin with no waiting root binds nothing`() {
        begin("1")
        add(root("a"))
        begin("2")
        projects("file:${root("a")}/")

        end("1")
        assertIs<ImportState.Importing>(states[root("a")])
        end("2")

        assertIs<ImportState.Done>(states[root("a")])
    }

    @Test
    fun `stopping forgets the roots that waited for a token`() {
        val a = root("a")
        val b = root("b")
        add(a)
        add(b)

        imports.onStopped()
        add(b)
        begin("1")
        projects("file:$b/")
        end("1")

        assertIs<ImportState.Done>(states[b])
    }

    @Test
    fun `Retry while another root imports cancels the waiting removal job`() {
        val x = root("x")
        val y = root("y")
        projects("file:$x/", "file:$y/")
        add(x)
        begin("x1")
        end("x1")
        add(y)
        begin("y")

        remove(x)
        add(x)
        end("y")
        assertIs<ImportState.Done>(states[y])
        begin("x2")
        end("x2")

        assertIs<ImportState.Done>(states[x])
    }

    @Test
    fun `a removal cancels the add that still waits behind another job, whose token never comes`() {
        val y = root("y")
        val a = root("a")
        projects("file:$y/")
        add(y)
        add(a)

        remove(a)
        begin("y")
        end("y")
        assertEquals(listOf("java.project.getAll", "java.project.getSettings"), access.commands.map { it.first })
        begin("removal")
        end("removal")

        assertEquals(2, access.commands.size, "no job of the cancelled add ran")
        assertNull(states[a])
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a removal keeps the add job that may run, so the next root's import is not judged a job early`(
        begunBeforeRemoval: Boolean,
    ) {
        val a = root("a")
        val b = root("b")
        projects()
        add(a)
        add(b)
        if (begunBeforeRemoval) begin("add")

        remove(a)
        if (!begunBeforeRemoval) begin("add")
        end("add")
        assertEquals(ImportState.Importing(now), states[b])
        projects("file:$b/")
        begin("b")
        end("b")
        begin("removal")
        end("removal", "Updating workspace folders")

        assertIs<ImportState.Done>(states[b])
        assertFalse(ticking)
    }

    @Test
    fun `a status of another type or text or shape does not acknowledge a notification`() {
        val a = root("a")
        projects("file:$a/")
        imports.onFolderAdded(a)
        val text = "Updating workspace folders: Adding 1 folder(s), removing 0 folders."

        listOf(
            """{"type":"Started","message":"$text"}""",
            """{"type":"Message","message":"ServiceReady"}""",
            """{"type":"Message","message":"Not $text"}""",
            """{"type":"Message","message":"$text Then more."}""",
            """{"type":{"x":1},"message":"$text"}""",
            """{"type":"Message","message":["$text"]}""",
            """{"message":"$text"}""",
            "\"text\"",
        ).forEach { json ->
            imports.onStatus(JsonParser.parseString(json))
            begin("early")
            end("early")
            assertEquals(emptyList(), access.commands, json)
        }
        imports.onStatus(null)
        begin("early")
        end("early")
        assertEquals(emptyList(), access.commands)

        status(1, 0)
        begin("import")
        end("import")

        assertIs<ImportState.Done>(states[a])
    }

    @Test
    fun `stopping forgets the notifications that waited for a status`() {
        val a = root("a")
        projects("file:$a/")
        imports.onFolderAdded(a)

        imports.onStopped()
        add(a)
        begin("1")
        end("1")

        assertIs<ImportState.Done>(states[a])
    }

    @Test
    fun `a second begin while the head job has its token binds nothing`() {
        val a = root("a")
        projects("file:$a/")
        add(a)
        begin("job")

        begin("stray")
        end("stray")

        assertEquals(emptyList(), access.commands)
        end("job")
        assertEquals("java.project.getAll", access.commands.first().first)
    }

    @Test
    fun `a removal cancels only the waiting add of its own root`() {
        val head = root("head")
        val a = root("a")
        val b = root("b")
        projects("file:$head/", "file:$a/", "file:$b/")
        add(head)
        add(b)
        add(a)

        remove(a)
        begin("head")
        end("head")
        begin("b")
        end("b")

        assertIs<ImportState.Done>(states[b])
    }

    @Test
    fun `a compliance that is no text is not taken`() {
        val a = root("a")
        projects("file:$a/")
        access.answers["java.project.getSettings"] =
            { CompletableFuture.completedFuture(mapOf("org.eclipse.jdt.core.compiler.compliance" to 21.0)) }
        add(a)

        begin("1")
        end("1")

        assertEquals(ImportState.Done(null, listOf(a)), states[a])
    }

    @ParameterizedTest
    @CsvSource(
        "add,1,0,true",
        "add,0,0,false",
        "add,1,1,false",
        "add,0,1,false",
        "add,2,0,false",
        "remove,0,1,true",
        "remove,0,0,false",
        "remove,1,1,false",
        "remove,1,0,false",
        "remove,0,2,false",
        "refresh,1,1,true",
        "refresh,1,0,false",
        "refresh,0,1,false",
        "refresh,2,1,false",
        "refresh,1,2,false",
    )
    fun `a status fits a notification by its counts alone, others are a mismatch`(
        kind: String,
        added: Int,
        removed: Int,
        fits: Boolean,
    ) {
        val a = root("a")
        when (kind) {
            "add" -> imports.onFolderAdded(a)
            "remove" -> imports.onFolderRemoved(a)
            else -> imports.onFolderRefreshed(a)
        }

        status(added, removed)

        assertEquals(!fits, warnings.isNotEmpty())
    }

    @Test
    fun `a mismatch is logged once however often it happens`() {
        imports.onFolderAdded(root("a"))
        imports.onFolderAdded(root("b"))

        status(0, 1)
        status(1, 1)

        assertEquals(1, warnings.size)
    }

    @Test
    fun `a mismatch keeps the begun job and its token and queues the importing roots as acknowledged`() {
        val a = root("a")
        val b = root("b")
        projects("file:$a/", "file:$b/")
        add(a)
        begin("a")
        remove(root("x")) // A removal job that has not begun: the resync drops it.
        imports.onFolderAdded(b)

        status(0, 1)
        end("a")
        begin("b")
        end("b")

        assertIs<ImportState.Done>(states[a])
        assertIs<ImportState.Done>(states[b])
        assertEquals(1, warnings.size)
    }

    @Test
    fun `a mismatch does not queue a root whose notification still waits for its status`() {
        val b = root("b")
        val c = root("c")
        projects("file:$b/", "file:$c/")
        imports.onFolderAdded(b)
        imports.onFolderAdded(c)

        status(0, 1)
        status(1, 0)
        begin("b")
        end("b")
        begin("c")
        end("c")

        assertIs<ImportState.Done>(states[b])
        assertIs<ImportState.Done>(states[c])
        assertFalse(ticking)
    }

    @Test
    fun `after a mismatch the importing roots are queued in the order they were announced, only those`() {
        val done = root("done")
        val roots = (1..40).map { root("r$it") }.shuffled(java.util.Random(7))
        projects(*(roots + done).map { "file:$it/" }.toTypedArray())
        add(done)
        begin("done")
        end("done")
        roots.forEach(::add)
        imports.onFolderAdded(root("extra"))

        status(0, 1)
        begin("first")
        end("first")
        begin("second")
        end("second")

        assertIs<ImportState.Done>(states[roots[0]])
        assertIs<ImportState.Done>(states[roots[1]])
        assertEquals(roots.size - 1, states.values.count { it is ImportState.Importing })
    }

    @Test
    fun `a status that waits longer than the settle time for its notification is dropped and what it held goes on`() {
        val a = root("a")
        projects("file:$a/")
        add(a)

        begin("build", "Building") // Open: jdtls is not silent, nothing is settled.
        status(0, 1) // No notification ever comes for this one or the next.
        status(1, 1)
        begin("a")
        end("a")
        end("build", "Building")
        tick(SETTLE.seconds - 1)
        assertEquals(ImportState.Importing(now), states[a])
        tick(1)
        assertEquals(
            listOf(
                "jdtls's workspace folder status (adding 0, removing 1) does not fit the notifications sent; " +
                    "the import tracking starts over",
            ),
            warnings,
        )
        tick(SETTLE.seconds - 1)
        assertEquals(ImportState.Importing(now), states[a], "the next status waits its own time")
        tick(1)

        assertIs<ImportState.Done>(states[a])
    }

    @Test
    fun `an event behind a status that waits for its notification waits too`() {
        val a = root("a")
        projects("file:$a/")
        add(a)
        begin("1")
        end("1")

        deferWorker = true
        status(1, 1)
        event300("file://$a/")
        imports.onFolderRefreshed(a)
        assertEquals(ImportState.Importing(now), states[a])
        begin("2")
        end("2")
        queued.forEach(Runnable::run)

        assertEquals(
            "Gradle can't run on JDK 25.0.5 (newest: 24). Choose another JDK in Project Settings → Java, " +
                "or upgrade the Gradle wrapper to 9.7.1.",
            assertIs<ImportState.Failed>(states[a]).detail,
        )
    }

    @Test
    fun `a job the mirror is sure of waits for its begin however long jdtls is silent`() {
        val a = root("a")
        projects("file:$a/")
        add(a)
        add(root("b")) // Its status finds a's job not begun, which it does not cancel.

        repeat(6) { tick(SETTLE.seconds) }

        assertEquals(ImportState.Importing(now), states[a])
        assertTrue(ticking)
        begin("a")
        end("a")
        assertIs<ImportState.Done>(states[a])
    }

    @Test
    fun `the doubt of a kept job ends when every job it could have shifted ended`() {
        val a = root("a")
        val b = root("b")
        projects("file:$a/", "file:$b/")
        add(a)
        // Retry on an idle jdtls (a.txt 6091-6100): the removal job is kept, and it did run.
        imports.onFolderRemoved(a)
        imports.onFolderAdded(a)
        status(0, 1)
        status(1, 0)
        listOf("removal", "import").forEach {
            begin(it)
            end(it)
        }
        assertIs<ImportState.Done>(states[a])

        add(b) // Its job waits behind something without a token: the mirror is sure of it.
        repeat(3) { tick(SETTLE.seconds) }

        assertEquals(ImportState.Importing(now), states[b])
    }

    @Test
    fun `a root settled too early is corrected by the end of the job that begins after all`() {
        val x = root("x")
        val y = root("y")
        projects("file:$x/")
        add(x)
        begin("x")
        end("x")
        imports.onFolderRemoved(x)
        imports.onFolderAdded(y)
        status(0, 1)
        status(1, 0)
        // Both jobs wait behind work without a token for longer than the settle time.
        tick(SETTLE.seconds)
        assertIs<ImportState.Failed>(states[y])

        begin("removal")
        end("removal", "Updating workspace folders")
        assertIs<ImportState.Failed>(states[y])
        projects("file:$y/")
        begin("import")
        end("import")

        assertIs<ImportState.Done>(states[y])
    }

    @Test
    fun `a later getAll that lists a failed root's project makes it done, one failed by event 300 stays failed`() {
        val a = root("a")
        val g = root("g")
        val b = root("b")
        projects()
        add(a)
        add(g)
        event300("file://$g/")
        listOf("a", "g").forEach {
            begin(it)
            end(it)
        }
        assertIs<ImportState.Failed>(states[a])
        assertIs<ImportState.Failed>(states[g])
        projects("file:$a/", "file:$g/", "file:$b/")
        add(b)

        begin("b")
        end("b")

        assertIs<ImportState.Done>(states[a])
        assertIs<ImportState.Failed>(states[g])
    }
}
