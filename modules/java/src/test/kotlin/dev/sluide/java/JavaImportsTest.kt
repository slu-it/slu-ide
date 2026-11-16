package dev.sluide.java

import com.google.gson.JsonParser
import dev.sluide.languageservers.LanguageServerNotRunningException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The import states of the roots: end, failure, events, ticker and close; [JavaImportsQueueTest] has the job mirror. */
internal class JavaImportsTest : JavaImportsFixture() {
    @Test
    fun `an announced root is importing since the clock's now`() {
        add(root("a"))

        assertEquals(ImportState.Importing(now), states[root("a")])
    }

    @Test
    fun `a project listed below the root ends the import, in both URI forms and for a Gradle subproject`() {
        val a = root("a")
        val b = root("b")
        val c = root("c")
        listOf(a, b, c).forEach(::add)
        projects("file:$a/", "file://${b.resolve("app")}/", "file:///elsewhere/c/")

        listOf("1", "2", "3").forEach {
            begin(it)
            end(it)
        }

        assertIs<ImportState.Done>(states[a])
        assertIs<ImportState.Done>(states[b])
        assertIs<ImportState.Failed>(states[c])
    }

    @Test
    fun `a project that only shares the root's name as a prefix is not below it`() {
        val a = root("app")
        add(a)
        begin("1")
        projects("file:${root("app2")}/")

        end("1")

        assertIs<ImportState.Failed>(states[a])
    }

    @Test
    fun `an end without a project below the root fails with the first error of its pom`() {
        val a = root("a")
        error(a.resolve("pom.xml"), "Malformed POM")
        add(a)
        begin("1")
        projects()

        end("1")

        val failed = assertIs<ImportState.Failed>(states[a])
        assertEquals("Malformed POM", failed.detail)
        assertEquals(listOf(a), actionsMade)
        assertEquals(1, failed.actions.size)
    }

    @Test
    fun `compliance is asked on the first project below the root and shown once known`() {
        val a = root("a")
        add(a)
        begin("1")
        val uri = "file:${a.resolve("core")}/"
        projects("file:/elsewhere/x/", uri, "file:${a.resolve("app")}/")
        access.answers["java.project.getSettings"] =
            { CompletableFuture.completedFuture(mapOf("org.eclipse.jdt.core.compiler.compliance" to "21")) }

        end("1")

        assertEquals(ImportState.Done("21", listOf(a.resolve("core"), a.resolve("app"))), states[a])
        assertEquals(
            "java.project.getSettings" to listOf<Any?>(uri, listOf("org.eclipse.jdt.core.compiler.compliance")),
            access.commands.last(),
        )
    }

    @Test
    fun `a failing compliance query leaves the import done without one`() {
        val a = root("a")
        add(a)
        begin("1")
        projects("file:$a/")
        access.answers["java.project.getSettings"] =
            { CompletableFuture.failedFuture(LanguageServerNotRunningException("not running")) }

        end("1")

        assertEquals(ImportState.Done(null, listOf(a)), states[a])
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a failing getAll is asked again after the settle time, a second failure fails the root`(
        secondAnswers: Boolean,
    ) {
        val done = root("done")
        val a = root("a")
        val answers =
            ArrayDeque(
                listOf<CompletableFuture<Any?>>(
                    CompletableFuture.completedFuture(listOf("file:$done/")),
                    CompletableFuture.failedFuture(LanguageServerNotRunningException("not running")),
                    if (secondAnswers) {
                        CompletableFuture.completedFuture(listOf("file:$a/"))
                    } else {
                        CompletableFuture.failedFuture(LanguageServerNotRunningException("still not running"))
                    },
                ),
            )
        access.answers["java.project.getAll"] = { answers.removeFirst() }
        add(done)
        begin("0")
        end("0")
        add(a)
        begin("1")

        end("1")
        assertEquals(ImportState.Importing(now), states[a])
        tick(SETTLE.seconds - 1)
        assertEquals(2, access.commands.count { it.first == "java.project.getAll" })
        val changes = access.workspaceChanges
        tick(1)

        assertEquals(3, access.commands.count { it.first == "java.project.getAll" })
        assertEquals(changes + 1, access.workspaceChanges)
        assertIs<ImportState.Done>(states[done], "only the roots that wait for their verdict fail")
        if (secondAnswers) {
            assertIs<ImportState.Done>(states[a])
        } else {
            assertEquals(
                "jdtls did not list its projects: still not running",
                assertIs<ImportState.Failed>(states[a]).detail,
            )
        }
        assertFalse(ticking)
        imports.onFolderAdded(root("b")) // Ticks again: the getAll is not asked a third time.
        tick(SETTLE.seconds)
        assertEquals(3, access.commands.count { it.first == "java.project.getAll" })
    }

    @Test
    fun `event 300 before the end fails the root and a later getAll that lists nothing keeps it failed`() {
        val a = root("a")
        add(a)
        begin("1")

        event300("file://$a/")
        projects()
        end("1")

        val failed = assertIs<ImportState.Failed>(states[a])
        assertEquals(
            "Gradle can't run on JDK 25.0.5 (newest: 24). Choose another JDK in Project Settings → Java, " +
                "or upgrade the Gradle wrapper to 9.7.1.",
            failed.detail,
        )
    }

    @Test
    fun `event 300 names the wrapper's Gradle version when the root has one`() {
        val a = root("a")
        a
            .resolve(
                "gradle/wrapper/gradle-wrapper.properties",
            ).file("distributionUrl=https\\://x/gradle-8.14.3-all.zip\n")
        add(a)

        event300("file://$a/")

        assertTrue(assertIs<ImportState.Failed>(states[a]).detail.startsWith("Gradle 8.14.3 can't run on JDK 25.0.5"))
    }

    @Test
    fun `event 300 for a root that getAll lists keeps it failed`() {
        val a = root("a")
        add(a)
        begin("1")
        projects("file:$a/")
        event300("file://${a.resolve("sub")}/")

        end("1")

        assertIs<ImportState.Failed>(states[a])
    }

    @Test
    fun `event 300 for a project outside every root, another event type and none at all change nothing`() {
        val a = root("a")
        add(a)

        event300("file://${root("b")}/")
        imports.onEvent(JsonParser.parseString("""{"eventType":200,"data":{"projectUri":"file://$a/"}}"""))
        imports.onEvent(null)
        imports.onEvent(JsonParser.parseString("""{"eventType":300}"""))
        imports.onEvent(JsonParser.parseString("""{"eventType":300,"data":{"projectUri":"http://x/"}}"""))

        assertEquals(ImportState.Importing(now), states[a])
    }

    @Test
    fun `event 300 goes to the innermost root that holds the project`() {
        val outer = root("a")
        val inner = root("a/b")
        add(outer)
        add(inner)

        event300("file://${inner.resolve("x")}/")

        assertIs<ImportState.Importing>(states[outer])
        assertIs<ImportState.Failed>(states[inner])
    }

    @Test
    fun `removing and announcing a root again gives importing again`() {
        val a = root("a")
        add(a)
        begin("1")
        projects("file:$a/")
        end("1")
        assertIs<ImportState.Done>(states[a])

        remove(a)
        assertNull(states[a])
        add(a)

        assertEquals(ImportState.Importing(now), states[a])
    }

    @Test
    fun `a failed root that is removed and announced again is importing`() {
        val a = root("a")
        add(a)
        begin("0")
        event300("file://$a/")
        assertIs<ImportState.Failed>(states[a])

        remove(a)
        add(a)
        assertEquals(ImportState.Importing(now), states[a])
        end("0")
        begin("1")
        projects("file:$a/")
        end("1")

        assertIs<ImportState.Done>(states[a])
    }

    @Test
    fun `stopping clears the states, the waiting roots and the bound tokens`() {
        val a = root("a")
        val b = root("b")
        add(a)
        add(b)
        begin("1")

        imports.onStopped()

        assertTrue(states.isEmpty())
        projects("file:$a/", "file:$b/")
        end("1")
        assertEquals(emptyList(), access.commands)
        // The roots come back after the restart; the first begin belongs to the first of them again.
        add(b)
        begin("2")
        end("2")
        assertIs<ImportState.Done>(states[b])
    }

    @Test
    fun `an answer of a server that stopped meanwhile is dropped`() {
        val a = root("a")
        val answer = CompletableFuture<Any?>()
        access.answers["java.project.getAll"] = { answer }
        add(a)
        begin("1")
        end("1")

        imports.onStopped()
        add(a)
        answer.complete(listOf("file:$a/"))

        assertIs<ImportState.Importing>(states[a])
    }

    @Test
    fun `the workspace refreshes once per state change`() {
        val a = root("a")
        add(a)
        assertEquals(1, access.workspaceChanges)
        begin("1")
        assertEquals(1, access.workspaceChanges)
        projects("file:$a/")

        end("1")

        assertEquals(2, access.workspaceChanges)
    }

    @Test
    fun `while a root imports the ticker refreshes and it stops once nothing imports`() {
        val a = root("a")
        add(a)
        assertEquals(1, tickers.size)
        val before = access.workspaceChanges

        tickers.single().run()
        assertEquals(before + 1, access.workspaceChanges)

        begin("1")
        projects("file:$a/")
        end("1")
        assertEquals(1, tickersStopped.size)
        val after = access.workspaceChanges
        tickers.single().run()
        assertEquals(after, access.workspaceChanges, "a tick after the stop refreshes nothing")
    }

    @Test
    fun `one ticker serves several importing roots and a new import after the stop starts another`() {
        add(root("a"))
        add(root("b"))
        assertEquals(1, tickers.size)

        projects("file:${root("a")}/", "file:${root("b")}/")
        begin("1")
        end("1")
        assertTrue(tickersStopped.isEmpty())
        begin("2")
        end("2")
        add(root("c"))

        assertEquals(1, tickersStopped.size)
        assertEquals(2, tickers.size)
    }

    @Test
    fun `removing the importing root or a stop also stops the ticker`() {
        add(root("a"))
        remove(root("a"))
        assertEquals(1, tickersStopped.size)
        add(root("b"))
        imports.onStopped()
        assertEquals(2, tickersStopped.size)
    }

    @Test
    fun `closing stops the ticker and later calls do nothing`() {
        val a = root("a")
        add(a)

        imports.close()

        assertEquals(1, tickersStopped.size)
        add(root("b"))
        assertNull(states[root("b")])
        assertFalse(tickers.size > 1)
    }

    @Test
    fun `a progress value that is no object or has no kind changes nothing`() {
        add(root("a"))

        imports.onProgress("1", JsonParser.parseString("\"text\""))
        imports.onProgress("1", JsonParser.parseString("{}"))
        imports.onProgress(
            "1",
            JsonParser.parseString("""{"kind":"report","message":"x","title":"Updating workspace folders"}"""),
        )
        begin("2")
        projects("file:${root("a")}/")
        end("1")

        assertIs<ImportState.Importing>(states[root("a")])
    }

    @Test
    fun `values of the wrong type in a progress or an event are ignored`() {
        val a = root("a")
        add(a)

        imports.onProgress("1", JsonParser.parseString("""{"kind":{"x":1}}"""))
        imports.onProgress("1", JsonParser.parseString("""{"kind":"begin","title":["Updating workspace folders"]}"""))
        imports.onEvent(JsonParser.parseString("""{"eventType":{"x":1},"data":{"projectUri":"file://$a/"}}"""))
        imports.onEvent(JsonParser.parseString("""{"eventType":300,"data":{"projectUri":{"x":"file://$a/"}}}"""))
        imports.onEvent(
            JsonParser.parseString(
                """{"eventType":300,"data":{"projectUri":"file://$a/","highestJavaVersion":["24"],"recommendedGradleVersion":{}}}""",
            ),
        )

        projects("file:$a/")
        end("1")
        assertEquals(emptyList(), access.commands, "the begin with a title that is no text bound no token")
        assertEquals(
            "Gradle can't run on JDK 25.0.5. Choose another JDK in Project Settings → Java.",
            assertIs<ImportState.Failed>(states[a]).detail,
        )
    }

    @Test
    fun `nothing changes after closing`() {
        val a = root("a")
        add(a)
        begin("1")
        projects("file:$a/")
        imports.onFolderAdded(root("b"))
        imports.close()
        val refreshes = access.workspaceChanges

        end("1")
        status(0, 1)
        remove(a)
        imports.onStopped()
        deferWorker = true
        event300("file://$a/")
        tickers.single().run()

        assertEquals(ImportState.Importing(now), states[a])
        assertEquals(emptyList(), access.commands)
        assertEquals(emptyList(), queued)
        assertEquals(refreshes, access.workspaceChanges)
        assertEquals(emptyList(), warnings, "a status after closing reaches no mirror")
    }

    @Test
    fun `announcing a failed root again without removing it imports it again`() {
        val a = root("a")
        add(a)
        event300("file://$a/")
        assertIs<ImportState.Failed>(states[a])

        add(a)
        begin("old")
        end("old")
        begin("new")
        projects("file:$a/")
        end("new")

        assertIs<ImportState.Done>(states[a])
    }

    @Test
    fun `a failure worked out on the worker does not come back for a root that was removed`() {
        val a = root("a")
        add(a)
        deferWorker = true
        event300("file://$a/")

        remove(a)
        queued.forEach(Runnable::run)

        assertNull(states[a])
    }

    @Test
    fun `a failure worked out on the worker does not reach a closed session`() {
        val a = root("a")
        add(a)
        deferWorker = true
        event300("file://$a/")

        imports.close()
        queued.forEach(Runnable::run)

        assertEquals(ImportState.Importing(now), states[a])
    }

    @Test
    fun `a failure worked out for an earlier announcement does not fail the new one`() {
        val a = root("a")
        add(a)
        deferWorker = true
        event300("file://$a/")
        remove(a)
        add(a)
        event300("file://$a/")
        assertEquals(2, queued.size)

        queued.first().run()

        assertEquals(ImportState.Importing(now), states[a])
        queued.last().run()
        assertIs<ImportState.Failed>(states[a])
    }

    @Test
    fun `an event that arrives while getAll is still being answered keeps the root from being done`() {
        val a = root("a")
        val answer = CompletableFuture<Any?>()
        access.answers["java.project.getAll"] = { answer }
        add(a)
        begin("1")
        end("1")
        deferWorker = true
        event300("file://$a/")

        answer.complete(listOf("file:$a/"))
        assertEquals(ImportState.Importing(now), states[a])
        queued.forEach(Runnable::run)

        assertIs<ImportState.Failed>(states[a])
    }

    @Test
    fun `an answer of the compliance query counts as a state change`() {
        val a = root("a")
        val answer = CompletableFuture<Any?>()
        access.answers["java.project.getSettings"] = { answer }
        add(a)
        begin("1")
        projects("file:$a/")
        end("1")
        val before = access.workspaceChanges

        answer.complete(mapOf("org.eclipse.jdt.core.compiler.compliance" to "21"))

        assertEquals(before + 1, access.workspaceChanges)
    }

    @Test
    fun `a failure counts as a state change, whether no project or event 300 gave it`() {
        add(root("a"))
        add(root("b"))
        begin("1")
        projects()
        val before = access.workspaceChanges

        end("1")
        event300("file://${root("b")}/")

        assertEquals(before + 2, access.workspaceChanges)
    }

    @Test
    fun `a compliance that comes after the root failed does not make it done again`() {
        val a = root("a")
        val answer = CompletableFuture<Any?>()
        access.answers["java.project.getSettings"] = { answer }
        projects("file:$a/")
        add(a)
        begin("1")
        end("1")
        event300("file://$a/")
        assertIs<ImportState.Failed>(states[a])

        answer.complete(mapOf("org.eclipse.jdt.core.compiler.compliance" to "21"))

        assertIs<ImportState.Failed>(states[a])
    }

    @Test
    fun `the compliance of an earlier import or of a closed session is not taken`() {
        val a = root("a")
        val answers = ArrayDeque<CompletableFuture<Any?>>()
        access.answers["java.project.getSettings"] = { CompletableFuture<Any?>().also(answers::addLast) }
        projects("file:$a/")
        val compliance = mapOf("org.eclipse.jdt.core.compiler.compliance" to "8")
        add(a)
        begin("1")
        end("1")
        remove(a)
        add(a)
        begin("removal")
        end("removal")
        begin("2")
        end("2")
        assertEquals(2, answers.size)

        answers.first().complete(compliance)
        assertEquals(ImportState.Done(null, listOf(a)), states[a])
        imports.close()
        answers.last().complete(mapOf("org.eclipse.jdt.core.compiler.compliance" to "21"))

        assertEquals(ImportState.Done(null, listOf(a)), states[a])
    }

    @Test
    fun `the end of a removed root's import brings it back as nothing`() {
        val a = root("a")
        add(a)
        begin("1")
        remove(a)
        projects("file:$a/")

        end("1")

        assertNull(states[a])
    }

    @Test
    fun `a failed root is not judged again when another root's import ends`() {
        val a = root("a")
        val b = root("b")
        projects()
        add(a)
        begin("a")
        end("a")
        val failed = states[a]
        add(b)

        begin("b")
        end("b")

        assertSame(failed, states[a])
        assertEquals(listOf(a, b), actionsMade)
    }

    @Test
    fun `the failed roots are listed`() {
        val a = root("a")
        val b = root("b")
        add(a)
        add(b)
        event300("file://$a/")

        assertEquals(listOf(a), imports.failedRoots())
    }

    /** Gives the offer a Gradle root whose import is done with [listed] listed below it; git has not answered. */
    private fun done(
        a: Path,
        vararg listed: Path,
    ) {
        outputTools[a] = BuildTool.GRADLE
        // Git is asked about folders with a file only.
        listed.forEach { it.resolve("build/jdtls-bin/A.class").file("x") }
        add(a)
        begin("import-$a")
        projects(*listed.map { "file:$it/" }.toTypedArray())
        end("import-$a")
    }

    private fun answerOutput(unignored: List<Path>?) {
        assertTrue(outputAnswers.isNotEmpty(), "git was asked")
        outputAnswers.removeFirst()(unignored)
    }

    private fun buildEnds(token: String) {
        begin(token, "Building")
        end(token)
    }

    @Test
    fun `a done root keeps the project folders getAll listed below it and no others`() {
        val a = root("a")

        done(a, root("a/core"), a, root("other"))

        assertEquals(listOf(root("a/core"), a), assertIs<ImportState.Done>(states[a]).projects)
    }

    @Test
    fun `a root that is done asks git about the output folders of its projects`() {
        val a = root("a")

        done(a, root("a/core"), a)

        assertEquals(listOf(listOf(root("a/core/build/jdtls-bin"), root("a/build/jdtls-bin"))), outputAsked)
    }

    @Test
    fun `a root that is importing or failed is not asked about`() {
        val a = root("a")
        outputTools[a] = BuildTool.GRADLE
        add(a)
        assertEquals(emptyList(), outputAsked)

        projects()
        begin("1")
        end("1")

        assertIs<ImportState.Failed>(states[a])
        assertEquals(emptyList(), outputAsked)
    }

    @Test
    fun `the end of a build token asks the done roots that have no answer`() {
        val a = root("a")
        done(a, a)
        answerOutput(null)

        buildEnds("b")

        assertEquals(2, outputAsked.size)
    }

    @Test
    fun `an answer of git settles the root until it is done again`() {
        val a = root("a")
        done(a, a)
        answerOutput(emptyList())

        buildEnds("b")
        assertEquals(1, outputAsked.size)
        refresh(a)
        buildEnds("c")
        assertEquals(1, outputAsked.size, "importing again, not done")
        begin("refresh")
        end("refresh")

        assertEquals(2, outputAsked.size, "done again")
    }

    @Test
    fun `a root that imports again is not asked about at a build's end until it is done`() {
        val a = root("a")
        done(a, a)
        answerOutput(null)

        refresh(a)
        buildEnds("b")

        assertEquals(1, outputAsked.size)
    }

    @Test
    fun `a done root that fails by event 300 is no longer asked about at a build's end`() {
        val a = root("a")
        done(a, a)
        answerOutput(null)

        event300("file://$a/")
        assertIs<ImportState.Failed>(states[a])
        buildEnds("b")

        assertEquals(1, outputAsked.size)
    }

    @Test
    fun `a removed root is no longer asked about at a build's end`() {
        val a = root("a")
        done(a, a)
        answerOutput(null)

        remove(a)
        buildEnds("b")

        assertEquals(1, outputAsked.size)
    }

    @Test
    fun `a stopped server leaves nothing to ask about at a build's end`() {
        val a = root("a")
        done(a, a)
        answerOutput(null)

        imports.onStopped()
        buildEnds("b")

        assertEquals(1, outputAsked.size)
    }
}
