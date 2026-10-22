package dev.sluide.previews.workflow

import dev.sluide.githubactions.RepositoryFiles
import dev.sluide.githubactions.WorkflowParser
import dev.sluide.githubactions.WorkflowSources
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** When [WorkflowPreview] parses, which result it keeps and when it has its view render again; no WebEngine. */
class WorkflowPreviewTest {
    private val file = Path.of("/repo/.github/workflows/ci.yml")

    /** Work handed to the parsing thread and back to the FX thread, run by the test when it says so. */
    private val background = ArrayDeque<Runnable>()
    private val fxThread = ArrayDeque<Runnable>()
    private val parsed = mutableListOf<String>()
    private var rerenders = 0

    private val parser =
        WorkflowParser { _, text ->
            parsed += text
            if (text.startsWith("fail")) error("broken parser")
            if (text == "flaky" && parsed.count { it == text } == 1) error("timed out")
            job(text.trim())
        }

    private fun preview(): WorkflowPreview =
        WorkflowPreview(parser, background::addLast, fxThread::addLast).apply { attach({ rerenders++ }) { null } }

    private fun runBackground() {
        while (background.isNotEmpty()) background.removeFirst().run()
    }

    private fun runFx() {
        while (fxThread.isNotEmpty()) fxThread.removeFirst().run()
    }

    private fun WorkflowPreview.page(text: String) = page(file, text, dark = false)

    private fun String.isReading() = WorkflowPreview.READING in this && "class=\"mermaid" !in this

    private fun String.isGraphOf(job: String) = "class=\"mermaid" in this && job in this

    @Test
    fun `the page says it reads the workflow, then the view renders again with the graph, which is kept`() {
        val preview = preview()

        assertTrue(preview.page("build").isReading())
        runBackground()
        assertEquals(0, rerenders, "only back on the FX thread")
        runFx()

        assertEquals(1, rerenders)
        assertTrue(preview.page("build").isGraphOf("build"))
        assertTrue(preview.page("build").isGraphOf("build"))
        assertEquals(listOf("build"), parsed)
    }

    @Test
    fun `a text being parsed is not parsed again when the view asks once more`() {
        val preview = preview()

        preview.page("build")
        preview.page("build")
        runBackground()
        runFx()

        assertEquals(listOf("build"), parsed)
        assertEquals(1, rerenders)
    }

    @Test
    fun `only the latest text matters, an outdated one is skipped or its graph dropped`() {
        val preview = preview()

        preview.page("old")
        // The old text's parsing ran, the new one came before its result.
        background.removeFirst().run()
        preview.page("new")
        runBackground()
        runFx()

        assertEquals(listOf("old", "new"), parsed)
        assertEquals(1, rerenders)
        assertTrue(preview.page("new").isGraphOf("new"))

        preview.page("queued")
        preview.page("newest")
        runBackground()
        runFx()
        assertEquals(listOf("old", "new", "newest"), parsed, "the queued text was outdated before its turn")
        assertTrue(preview.page("newest").isGraphOf("newest"))
    }

    @Test
    fun `going back to a text whose graph is kept drops the result of the text in between`() {
        val preview = preview()
        preview.page("first")
        runBackground()
        runFx()

        preview.page("second")
        assertTrue(preview.page("first").isGraphOf("first"))
        runBackground()
        runFx()

        assertEquals(1, rerenders)
        assertTrue(preview.page("first").isGraphOf("first"))
    }

    @Test
    fun `a moved file is parsed again under its new name`() {
        val preview = preview()
        preview.page("build")
        runBackground()
        runFx()

        assertTrue(preview.page(file.resolveSibling("release.yml"), "build", dark = false).isReading())
        runBackground()
        runFx()
        assertEquals(listOf("build", "build"), parsed)
    }

    @Test
    fun `after detach nothing is parsed or rendered`() {
        val preview = preview()
        preview.page("build")

        preview.detach()
        runBackground()
        runFx()
        assertTrue(preview.page("other").isReading())
        runBackground()
        runFx()

        assertEquals(0, rerenders)
        assertTrue(parsed.isEmpty(), "the queued parsing saw it was outdated")
    }

    @Test
    fun `a parser that fails is tried once more right away, after that only an edit parses again`() {
        val preview = preview()

        preview.page("fail")
        runBackground()
        runFx()
        assertEquals(1, rerenders)
        val failed = preview.page("fail")
        assertTrue("The workflow parser failed: broken parser" in failed, failed)
        assertFalse("class=\"mermaid" in failed)

        // Tried once more right away; after that, edit the file or reopen it.
        runBackground()
        runFx()
        assertEquals(listOf("fail", "fail"), parsed)
        assertEquals(2, rerenders)
        assertTrue("The workflow parser failed: broken parser" in preview.page("fail"))
        assertTrue("The workflow parser failed: broken parser" in preview.page("fail"))
        runBackground()
        runFx()
        assertEquals(listOf("fail", "fail"), parsed, "no loop")
        assertEquals(2, rerenders)

        assertTrue(preview.page("fail, edited").isReading())
        repeat(3) {
            runBackground()
            runFx()
        }
        assertEquals(listOf("fail", "fail", "fail, edited", "fail, edited"), parsed, "an edit is tried twice again")
    }

    @Test
    fun `a parser that fails once draws the graph when tried once more`() {
        val preview = preview()

        preview.page("flaky")
        runBackground()
        runFx()
        assertTrue("The workflow parser failed: timed out" in preview.page("flaky"))
        runBackground()
        runFx()

        assertEquals(listOf("flaky", "flaky"), parsed)
        assertEquals(2, rerenders)
        assertTrue(preview.page("flaky").isGraphOf("flaky"))
    }

    @Test
    fun `a workflow without jobs gives a note that is kept like a graph`() {
        val preview = preview()

        preview.page("")
        runBackground()
        runFx()

        assertTrue("No jobs in this workflow." in preview.page(""))
        assertTrue("No jobs in this workflow." in preview.page(""))
        assertEquals(listOf(""), parsed)
        assertEquals(1, rerenders)
    }

    @Test
    fun `a text parsed twice, while its graph came meanwhile, renders once`() {
        val preview = preview()

        preview.page("a")
        preview.page("b")
        preview.page("a")
        runBackground()
        runFx()

        assertEquals(listOf("a", "a"), parsed, "b was outdated before its turn")
        assertEquals(1, rerenders)
        assertTrue(preview.page("a").isGraphOf("a"))
    }

    /** A repository in [repo] whose `ci.yml` calls `called.yml`; returns `ci.yml`. */
    private fun callingRepository(repo: Path): Path {
        val workflows = repo.resolve(".github/workflows").createDirectories()
        workflows.resolve("called.yml").writeText("called")
        return workflows.resolve("ci.yml")
    }

    @Test
    fun `references are read from the workflow's repository, their parser failures retry once like the workflow's`(
        @TempDir repo: Path,
    ) {
        val file = callingRepository(repo)
        val preview =
            WorkflowPreview(
                WorkflowParser { fileName, _ ->
                    parsed += fileName
                    if (fileName == "called.yml") error("broken parser") else CALLS
                },
                background::addLast,
                fxThread::addLast,
            ).apply { attach({ rerenders++ }) { null } }

        preview.page(file, "calls", dark = false)
        runBackground()
        runFx()

        assertEquals(listOf("ci.yml", "called.yml"), parsed)
        assertEquals(1, rerenders)
        assertTrue("parser failed: broken parser" in preview.page(file, "calls", dark = false), "shown once")
        runBackground()
        runFx()

        assertEquals(listOf("ci.yml", "called.yml", "ci.yml", "called.yml"), parsed, "parsed once more")
        assertEquals(2, rerenders)
        assertTrue("parser failed: broken parser" in preview.page(file, "calls", dark = false), "then kept")
        assertTrue(background.isEmpty())
    }

    @Test
    fun `an outdated text stops reading its references and its graph is not kept`(
        @TempDir repo: Path,
    ) {
        val file = callingRepository(repo)
        lateinit var preview: WorkflowPreview
        preview =
            WorkflowPreview(
                WorkflowParser { fileName, text ->
                    parsed += "$fileName: $text"
                    if (text == "calls") {
                        // Edited while this text is parsed.
                        preview.page(file, "edited", dark = false)
                        CALLS
                    } else {
                        job(text)
                    }
                },
                background::addLast,
                fxThread::addLast,
            ).apply { attach({ rerenders++ }) { null } }

        preview.page(file, "calls", dark = false)
        runBackground()
        runFx()

        assertEquals(listOf("ci.yml: calls", "ci.yml: edited"), parsed, "called.yml not parsed for the outdated text")
        assertEquals(1, rerenders)
        assertTrue(preview.page(file, "edited", dark = false).isGraphOf("edited"))
    }

    @Test
    fun `only workflows in a repository's workflows folder read references`() {
        assertIs<RepositoryFiles>(WorkflowPreview.sources(Path.of("/repo/.github/workflows/ci.yml")))
        assertSame(WorkflowSources.NONE, WorkflowPreview.sources(Path.of("/repo/workflows/ci.yml")))
        assertSame(WorkflowSources.NONE, WorkflowPreview.sources(Path.of("/repo/.github/ci.yml")))
    }

    private companion object {
        /** The parser's JSON for a workflow calling `./.github/workflows/called.yml`. */
        const val CALLS =
            """{"schema": 1, "errors": [], "events": [], "jobs": [{"id": "call", "type": "reusableWorkflowJob",
            "needs": [], "uses": "./.github/workflows/called.yml"}]}"""

        /** The parser's JSON for a workflow with one job named [id], none if it is blank. */
        fun job(id: String): String {
            val jobs =
                if (id.isEmpty()) {
                    ""
                } else {
                    """{"id": "$id", "name": null, "line": 3, "type": "job", "needs": [], "if": null,
                    "runsOn": "ubuntu-latest", "matrix": null, "uses": null, "with": null, "secretsInherit": false,
                    "steps": [{"id": null, "name": null, "line": 5, "uses": null, "run": "echo $id", "if": null}]}"""
                }
            return """{"schema": 1, "errors": [], "name": null, "events": [], "jobs": [$jobs]}"""
        }
    }
}
