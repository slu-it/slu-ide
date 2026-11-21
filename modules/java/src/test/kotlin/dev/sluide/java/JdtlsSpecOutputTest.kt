package dev.sluide.java

import com.google.gson.JsonParser
import dev.sluide.languageservers.EnvironmentProblem
import dev.sluide.languageservers.ProblemAction
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The offer to ignore jdtls's compiled output as the spec shows it (ADR 0179); the logic is in `OutputIgnoreOffersTest`. */
internal class JdtlsSpecOutputTest : JdtlsSpecFixture() {
    private val gitAsked = java.util.concurrent.CopyOnWriteArrayList<List<Path>>()
    private val gitAnswered =
        java.util.concurrent.atomic
            .AtomicInteger()

    /** The window's git check, which answers with [answer] of the folders asked, once for each ask. */
    private fun gitSays(answer: (List<Path>) -> List<Path>?) {
        checks.register(window) { folders, done ->
            gitAsked += folders
            done(answer(folders))
            gitAnswered.incrementAndGet()
        }
    }

    private fun awaitBanner(file: Path): EnvironmentProblem {
        var problem: EnvironmentProblem? = null
        awaitCondition("a banner for $file") { spec.environmentProblem(file, window).also { problem = it } != null }
        return checkNotNull(problem)
    }

    private fun perform(
        problem: EnvironmentProblem,
        label: String,
    ) = assertIs<ProblemAction.Callback>(problem.actions.single { it.label == label }).perform()

    @Test
    fun `a done root whose output git does not ignore gets the offer banner with its own three buttons`() {
        val (maven, _, _) = fixtures()
        val access = ScriptedAccess()
        val session = javaSession(access)
        val root = assertNotNull(spec.workspaceFolder(maven, window))
        gitSays { it }

        finish(session, access, root)
        val problem = awaitBanner(maven)

        assertEquals(listOf(listOf(root.resolve("target"))), gitAsked)
        assertEquals(root, problem.folder)
        assertEquals("target isn't ignored by git; jdtls compiles into it.", problem.message)
        assertFalse(problem.dismissible)
        assertEquals(listOf("Add to .gitignore", "Not Now", "Don't Ask Again"), problem.actions.map { it.label })
        assertSame(problem, spec.environmentProblem(maven, window), "stable between recomputes")
    }

    @Test
    fun `the banner names several folders relative to the build root`() {
        val (maven, _, _) = fixtures()
        val access = ScriptedAccess()
        val session = javaSession(access)
        val root = assertNotNull(spec.workspaceFolder(maven, window))
        gitSays { it }
        root.resolve("target/A.class").file("x")
        root.resolve("sub/target/A.class").file("x")
        access.answers["java.project.getAll"] =
            { CompletableFuture.completedFuture(listOf("file:$root/", "file:${root.resolve("sub")}/")) }

        session.imports.onFolderAdded(root)
        session.imports.onStatus(added)
        session.imports.onProgress("t", begin)
        session.imports.onProgress("t", end)

        assertEquals(
            "target and sub/target aren't ignored by git; jdtls compiles into them.",
            awaitBanner(maven).message,
        )
    }

    @Test
    fun `a done root git cannot answer for has no offer`() {
        val (maven, _, _) = fixtures()
        val access = ScriptedAccess()
        val session = javaSession(access)
        val root = assertNotNull(spec.workspaceFolder(maven, window))
        gitSays { null }

        finish(session, access, root)
        awaitCondition("the answer") { gitAnswered.get() == 1 }

        assertNull(spec.environmentProblem(maven, window))
    }

    @Test
    fun `the importing and failed banners win over the offer`() {
        val (maven, _, _) = fixtures()
        val access = ScriptedAccess()
        val session = javaSession(access)
        val root = assertNotNull(spec.workspaceFolder(maven, window))
        gitSays { it }
        finish(session, access, root)
        awaitBanner(maven)

        session.imports.onEvent(
            JsonParser.parseString(
                """{"eventType":300,"data":{"projectUri":"file://$root/","highestJavaVersion":"24"}}""",
            ),
        )
        awaitCondition(
            "the failure",
        ) { spec.environmentProblem(maven, window)?.message?.startsWith("Import of") == true }
        assertTrue(assertNotNull(spec.environmentProblem(maven, window)).dismissible)

        session.imports.onFolderRefreshed(root)

        assertEquals("Importing app (Maven)… 0:00", spec.environmentProblem(maven, window)?.message)
    }

    @Test
    fun `Not Now hides the offer of that root for the session without asking the project to decline`() {
        val (maven, gradle, _) = fixtures()
        val access = ScriptedAccess()
        val session = javaSession(access)
        val mavenRoot = assertNotNull(spec.workspaceFolder(maven, window))
        val gradleRoot = assertNotNull(spec.workspaceFolder(gradle, window))
        gitSays { it }
        finish(session, access, mavenRoot)
        finish(session, access, gradleRoot)
        val problem = awaitBanner(maven)
        awaitBanner(gradle)

        perform(problem, "Not Now")

        assertNull(spec.environmentProblem(maven, window))
        assertNotNull(spec.environmentProblem(gradle, window))
        assertFalse(projectSettings.outputIgnoreDeclined(window))
    }

    @Test
    fun `Don't Ask Again stores the decision for the project and hides every offer but no import failure`() {
        val (maven, gradle, _) = fixtures()
        val access = ScriptedAccess()
        val session = javaSession(access)
        val mavenRoot = assertNotNull(spec.workspaceFolder(maven, window))
        val gradleRoot = assertNotNull(spec.workspaceFolder(gradle, window))
        gitSays { it }
        finish(session, access, mavenRoot)
        finish(session, access, gradleRoot)
        val problem = awaitBanner(maven)
        awaitBanner(gradle)

        perform(problem, "Don't Ask Again")
        // The decision is stored on the worker before the offers hear of it: wait for what the user sees.
        awaitCondition("the offers hidden") {
            spec.environmentProblem(maven, window) == null && spec.environmentProblem(gradle, window) == null
        }

        assertTrue(projectSettings.outputIgnoreDeclined(window))
        session.imports.onEvent(
            JsonParser.parseString(
                """{"eventType":300,"data":{"projectUri":"file://$gradleRoot/","highestJavaVersion":"24"}}""",
            ),
        )
        awaitCondition("the failure") {
            spec.environmentProblem(gradle, window)?.message?.startsWith("Import of") ==
                true
        }
    }

    @Test
    fun `a project that declined gets no git question at all`() {
        val (maven, _, _) = fixtures()
        val access = ScriptedAccess()
        val session = javaSession(access)
        val root = assertNotNull(spec.workspaceFolder(maven, window))
        projectSettings.declineOutputIgnore(window)
        gitSays { it }

        finish(session, access, root)
        Thread.sleep(150) // A negative check: no ask means no signal; the check hops over the session worker.

        assertEquals(emptyList(), gitAsked)
        assertNull(spec.environmentProblem(maven, window))
    }
}
