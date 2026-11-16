package dev.sluide.java

import com.google.gson.JsonParser
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The orders of folder notifications, `language/status` messages and `$/progress` tokens jdtls 1.63.0 sent when measured
 * with a client that starts it as Thoreau does (roots only), each ending in Done or Failed with the ticker stopped;
 * this test is the record of those traces, and each test names its lines. Only the "Updating workspace folders" tokens and the tokens open around a status matter;
 * the others are left out.
 */
internal class JavaImportsTraceTest : JavaImportsFixture() {
    private val update = "Updating workspace folders"

    /** A root whose first import found no project (a malformed pom), as Retry finds it. */
    private fun failed(name: String): Path {
        val root = root(name)
        add(root)
        begin("first-$name")
        end("first-$name", "Importing Maven project(s)")
        assertIs<ImportState.Failed>(states[root])
        access.commands.clear()
        return root
    }

    @Test
    fun `(a) Retry as two notifications on an idle jdtls runs the removal job and then the import`() {
        projects()
        val a = failed("a-bad")

        // a.txt 6091-6100: both statuses first, then the removal job's begin; that job had started.
        imports.onFolderRemoved(a)
        imports.onFolderAdded(a)
        status(0, 1)
        status(1, 0)
        begin("f48bcbf9")
        end("f48bcbf9", update)
        assertEquals(ImportState.Importing(now), states[a])
        assertEquals(emptyList(), access.commands, "the end of the removal job asks nothing")
        begin("5dacf1fd")
        end("5dacf1fd", "Importing Maven project(s)")

        assertIs<ImportState.Failed>(states[a])
        assertFalse(ticking)
    }

    @Test
    fun `(a) a refresh is one job whose end gives the verdict`() {
        projects()
        val a = failed("a-bad")
        projects("file:$a/")

        // a.txt 9229-9675: one notification that removes and adds the root, one status, one job.
        refresh(a)
        begin("c8387244")
        begin("549d2547", "LookupJDKToolchainsJob")
        end("549d2547", "LookupJDKToolchainsJob")
        end("c8387244")

        assertIs<ImportState.Done>(states[a])
        assertFalse(ticking)
    }

    @Test
    fun `(b) Retry as two notifications behind an auto-build ends failed once jdtls is silent`() {
        projects()
        val a = failed("b2-bad")

        // b2.txt 27232-35141: jdtls cancelled the waiting removal job on the add's status; one job ran, the add's.
        begin("6f85b725", "Building")
        imports.onFolderRemoved(a)
        imports.onFolderAdded(a)
        status(0, 1)
        status(1, 0)
        end("6f85b725", "Building")
        begin("6a1961a6")
        end("6a1961a6", "Importing Maven project(s)")
        begin("ac07cddb", "Building")
        tick(SETTLE.seconds) // The build still runs: whatever waits may wait behind it.
        end("ac07cddb", "Building")
        begin("816cff13", "Building")
        end("816cff13", "Building")
        tick(SETTLE.seconds - 1)
        assertEquals(ImportState.Importing(now), states[a], "the mirror cannot see the cancellation")

        tick(1)

        assertIs<ImportState.Failed>(states[a])
        assertFalse(ticking)
    }

    @Test
    fun `(b) a refresh behind an auto-build is one job whose end gives the verdict`() {
        projects()
        val a = failed("b2-bad")

        // b2.txt 40382-40387.
        begin("2cc3245d", "Building")
        refresh(a)
        assertEquals(ImportState.Importing(now), states[a])
        end("2cc3245d", "Building")
        begin("38642fa7")
        end("38642fa7", "Importing Maven project(s)")

        assertIs<ImportState.Failed>(states[a])
        assertFalse(ticking)
    }

    @Test
    fun `(b) a root removed and another added behind an auto-build ends done once jdtls is silent`() {
        val x = root("b2-x")
        val y = root("b2-y")
        projects("file:$x/")
        add(x)
        begin("x")
        end("x")
        projects("file:$x/", "file:$y/")

        // b2.txt 50296-58219: the removal job waited behind the build and was cancelled by the add's status.
        begin("86eb4d5a", "Building")
        imports.onFolderRemoved(x)
        imports.onFolderAdded(y)
        status(0, 1)
        status(1, 0)
        end("86eb4d5a", "Building")
        begin("00ca75f7")
        end("00ca75f7")
        begin("59b7477b", "Building")
        end("59b7477b", "Building")
        assertEquals(ImportState.Importing(now), states[y])
        tick(SETTLE.seconds)

        assertIs<ImportState.Done>(states[y])
        assertNull(states[x])
        assertFalse(ticking)
    }

    @Test
    fun `(b) a root removed and another added on an idle jdtls are two jobs`() {
        val x = root("b2-x")
        val y = root("b2-y")
        projects("file:$x/", "file:$y/")
        add(y)
        begin("y")
        end("y")

        // b2.txt 63419-63425.
        imports.onFolderRemoved(y)
        imports.onFolderAdded(x)
        status(0, 1)
        status(1, 0)
        begin("72f3584d")
        end("72f3584d", update)
        begin("01b5b6e7")
        end("01b5b6e7", "Importing Maven project(s)")

        assertIs<ImportState.Done>(states[x])
        assertFalse(ticking)
    }

    @Test
    fun `(c) a root removed while its add waits behind a long import, then another added`() {
        val y = root("c-y")
        val x = root("c-x")
        val z = root("c-z")
        projects("file:$y/", "file:$z/")

        // c.txt 971-2605: x's add and the removal job both wait behind y's import: both are cancelled.
        add(y)
        begin("38593654")
        imports.onFolderAdded(x)
        imports.onFolderRemoved(x)
        imports.onFolderAdded(z)
        status(1, 0)
        status(0, 1)
        status(1, 0)
        begin("4bd8e886", "Validating Gradle wrapper checksum...")
        end("4bd8e886", "Validating Gradle wrapper checksum...")
        end("38593654", "Importing root project")
        assertIs<ImportState.Done>(states[y])
        begin("40d0dbaa")
        end("40d0dbaa")

        assertNull(states[x])
        assertIs<ImportState.Done>(states[z])
        assertFalse(ticking)
    }

    @Test
    fun `(c) four notifications at once, the long import's begin after the removal's status`() {
        val y = root("c-y2")
        val x = root("c-x2")
        val z = root("c-z2")
        projects("file:$y/", "file:$z/")

        // c.txt 10154-10375: y's job had started, its begin came between the statuses.
        imports.onFolderAdded(y)
        imports.onFolderAdded(x)
        imports.onFolderRemoved(x)
        imports.onFolderAdded(z)
        status(1, 0)
        status(1, 0)
        status(0, 1)
        begin("ebf06c99")
        status(1, 0)
        end("ebf06c99", "Importing Gradle project(s)")
        begin("48b6b948")
        end("48b6b948")

        assertIs<ImportState.Done>(states[y])
        assertNull(states[x])
        assertIs<ImportState.Done>(states[z])
        assertFalse(ticking)
    }

    @Test
    fun `(d) a JDK change and a Retry as two notifications while jdtls updates a project`() {
        projects()
        val bad = failed("d-bad")

        // d.txt 7690-9925: the removal job waited behind "Update project d-g" and was cancelled.
        imports.onStatus(JsonParser.parseString("""{"type":"Message","message":"Updating d-g configuration"}"""))
        begin("e30ab85f", "Update project d-g")
        imports.onFolderRemoved(bad)
        imports.onFolderAdded(bad)
        status(0, 1)
        status(1, 0)
        begin("694ccce5")
        end("e30ab85f", "Update project d-g")
        end("694ccce5", "Importing Maven project(s)")
        begin("cb4ccacb", "Building")
        end("cb4ccacb", "Building")
        assertEquals(ImportState.Importing(now), states[bad])
        tick(SETTLE.seconds)

        assertIs<ImportState.Failed>(states[bad])
        assertFalse(ticking)
    }

    @Test
    fun `(d) a JDK change and then a refresh while jdtls updates a project`() {
        projects()
        val bad = failed("d-bad")

        // d.txt 15022-15045.
        imports.onStatus(JsonParser.parseString("""{"type":"Message","message":"Updating d-g configuration"}"""))
        begin("cc4320fc", "Update project d-g")
        refresh(bad)
        assertEquals(ImportState.Importing(now), states[bad])
        begin("2a641b9d")
        end("cc4320fc", "Update project d-g")
        end("2a641b9d", "Importing Maven project(s)")

        assertIs<ImportState.Failed>(states[bad])
        assertFalse(ticking)
    }

    @Test
    fun `(e) three roots added quickly import one after another`() {
        val roots = listOf(root("e-0"), root("e-1"), root("e-2"))
        projects(*roots.map { "file:$it/" }.toTypedArray())

        // e.txt 969-1409: the first job's begin came between the second and the third status.
        roots.forEach(imports::onFolderAdded)
        status(1, 0)
        status(1, 0)
        begin("a826c57c")
        status(1, 0)
        end("a826c57c")
        assertEquals(listOf(true, false, false), roots.map { states[it] is ImportState.Done })
        begin("8d4a7db0")
        end("8d4a7db0")
        begin("6b37f1df")
        end("6b37f1df")

        roots.forEach { assertIs<ImportState.Done>(states[it]) }
        assertFalse(ticking)
    }

    @Test
    fun `(f) the removal job waits behind a Gradle synchronization whose token stays open after it`() {
        projects()
        val bad = failed("f-bad")

        // f.txt 5064-5162: the import job ran while "Synchronize Gradle projects with workspace" was still open.
        begin("8c2c7207", "Synchronize Gradle projects with workspace")
        imports.onFolderRemoved(bad)
        imports.onFolderAdded(bad)
        status(0, 1)
        status(1, 0)
        begin("148c3c06")
        end("148c3c06", "Importing Maven project(s)")
        tick(SETTLE.seconds) // A token is open: nothing is settled.
        assertEquals(ImportState.Importing(now), states[bad])
        end("8c2c7207", "Synchronize Gradle projects with workspace")
        tick(SETTLE.seconds - 1)
        assertEquals(ImportState.Importing(now), states[bad], "the silence counts from the last token's end")
        tick(1)

        assertIs<ImportState.Failed>(states[bad])
        assertFalse(ticking)
    }

    @Test
    fun `(g) jdtls cancels the removal job though no token is open at the add's status`() {
        projects()
        val bad = failed("g-bad")

        // g.txt 59722-59735 (g-bg3). The same input in g-bg0, 1, 2 and 4 ran the removal job (two jobs, as in (a)):
        // whether it had started when the add's status went out is a race inside jdtls.
        imports.onFolderRemoved(bad)
        imports.onFolderAdded(bad)
        status(0, 1)
        begin("4464073a", "Background task")
        end("4464073a", "Background task")
        status(1, 0)
        begin("c9440d78")
        end("c9440d78", "Importing Maven project(s)")
        tick(SETTLE.seconds)

        assertIs<ImportState.Failed>(states[bad])
        assertFalse(ticking)
    }

    @Test
    fun `a status that reaches the FX thread before its notification waits for it, with what came after it`() {
        val a = root("a")
        projects("file:$a/")

        // The status comes from the reader thread, the notification from the outbound thread after the write:
        // a.txt 9229-9230, a refresh's status and its begin within a millisecond.
        status(1, 0)
        begin("1")
        end("1")
        imports.onFolderAdded(a)
        assertIs<ImportState.Done>(states[a])
        status(1, 1)
        begin("2")
        assertIs<ImportState.Done>(states[a])
        imports.onFolderRefreshed(a)
        assertEquals(ImportState.Importing(now), states[a])
        end("2")

        assertIs<ImportState.Done>(states[a])
        assertEquals(emptyList(), warnings)
        assertFalse(ticking)
    }
}
