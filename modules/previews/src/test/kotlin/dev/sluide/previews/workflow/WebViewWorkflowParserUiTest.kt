package dev.sluide.previews.workflow

import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The bundled workflow parser in JavaFX's WebKit ([WebViewWorkflowParser]): the repository's workflows and the
 * bundle's fixtures give the jobs and error counts that the bundle gives in Node (`check.mjs`). Needs a display, see
 * the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class WebViewWorkflowParserUiTest {
    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    private fun parse(file: Path): JsonNode = mapper.readTree(parser.parse(file.name, file.readText()))

    private fun JsonNode.jobIds() = path("jobs").values().map { it.path("id").asString() }

    @Test
    fun `the repository's workflows parse without errors`() {
        val expected = mapOf("ci.yml" to listOf("build"), "release.yml" to listOf("publish"))
        for (file in WorkflowFiles.repository) {
            val json = parse(file)
            assertEquals(1, json.path("schema").asInt(), file.name)
            assertEquals(0, json.path("errors").size(), "${file.name}: ${json.path("errors")}")
            assertEquals(expected[file.name] ?: listOf("ui-tests"), json.jobIds(), file.name)
        }
        val ci = parse(WorkflowFiles.workflow("ci.yml"))
        assertEquals("CI", ci.path("name").asString())
        assertEquals(listOf("push", "pull_request"), ci.path("events").values().map { it.path("name").asString() })
        assertEquals(
            8,
            ci
                .path("jobs")
                .get(0)
                .path("steps")
                .size(),
        )
    }

    @Test
    fun `the fixtures give the jobs and errors they give in Node, broken files errors and no exception`() {
        // file to (number of errors, job ids), as `node check.mjs` prints them.
        val expected =
            mapOf(
                "broken-schema.yml" to (7 to listOf("build", "deploy")),
                "broken-syntax.yml" to (1 to emptyList()),
                "matrix.yml" to (0 to listOf("static", "setup", "dynamic", "partly-dynamic")),
                "needs-chain.yml" to (0 to listOf("lint", "test", "ui-test", "package", "always-report")),
                "not-a-workflow.yml" to (2 to emptyList()),
                "odd-steps-and-matrix.yml" to (5 to listOf("a", "b")),
                "reusable-and-events.yml" to (0 to listOf("local", "remote", "remote-explicit", "steps")),
                "schedule-as-string.yml" to (0 to listOf("a")),
                "schedule-in-list.yml" to (0 to listOf("a")),
                "schedule-while-typing.yml" to (1 to listOf("a")),
                "step-inputs.yml" to (0 to listOf("build")),
            )
        assertEquals(expected.keys, WorkflowFiles.fixtures.map { it.name }.toSet(), "a row per fixture")
        for (file in WorkflowFiles.fixtures) {
            val json = parse(file)
            val (errors, jobs) = checkNotNull(expected[file.name])
            assertEquals(1, json.path("schema").asInt(), file.name)
            assertEquals(errors, json.path("errors").size(), "${file.name}: ${json.path("errors")}")
            assertEquals(jobs, json.jobIds(), file.name)
        }
        val reusable = parse(WorkflowFiles.fixture("reusable-and-events.yml"))
        assertEquals(
            "owner/repo/.github/workflows/deploy.yml@v1",
            reusable
                .path("jobs")
                .get(1)
                .path("uses")
                .asString(),
        )
    }

    @Test
    fun `a step's with inputs reach the JSON as written, null without one`() {
        val steps = parse(WorkflowFiles.fixture("step-inputs.yml")).path("jobs").get(0).path("steps")

        assertEquals(
            listOf("fetch-depth", "ref", "submodules"),
            steps
                .get(0)
                .path("with")
                .propertyNames()
                .toList(),
        )
        assertEquals(
            "\${{ github.ref }}",
            steps
                .get(0)
                .path("with")
                .path("ref")
                .asString(),
        )
        assertEquals(
            "echo one\n\necho two\n",
            steps
                .get(1)
                .path("with")
                .path("script")
                .asString(),
        )
        assertTrue(steps.get(2).path("with").isNull)
        assertTrue(steps.get(3).path("with").isNull)
    }

    @Test
    fun `names and texts reach the parser as they are, never as script text`() {
        val name = "it's \"quoted\" \\ </script>.yml"
        // A double-quoted YAML string: \" is a quote, \\ a backslash.
        val yamlName = "\"a 'b' \\\"c\\\" </script> \\\\n `d` 😀\""
        val text = "on: push\njobs:\n  x:\n    name: $yamlName\n    runs-on: x\n    steps:\n      - run: echo\n"

        val json = mapper.readTree(parser.parse(name, text))

        assertEquals(0, json.path("errors").size(), json.path("errors").toString())
        assertEquals(
            "a 'b' \"c\" </script> \\n `d` 😀",
            json
                .path("jobs")
                .get(0)
                .path("name")
                .asString(),
        )
    }

    @Test
    fun `parsing on the FX thread is refused`() {
        val outcome = WaitForAsyncUtils.asyncFx<Result<String>> { runCatching { parser.parse("ci.yml", "on: push\n") } }
        assertIs<IllegalStateException>(outcome.get().exceptionOrNull())
    }

    @Test
    fun `a parser that does not answer in time throws, and so does the next call instead of waiting`() {
        val impatient = WebViewWorkflowParser(Duration.ZERO)
        repeat(2) {
            val error = assertFailsWith<IOException> { impatient.parse("ci.yml", "on: push\n") }
            assertTrue("did not answer" in error.message.orEmpty(), error.message)
        }
        // The shared engine of the other parser is not touched.
        assertEquals(1, mapper.readTree(parser.parse("ci.yml", "on: push\njobs: {}\n")).path("schema").asInt())
    }

    @Test
    fun `an engine that cannot be created fails each call at once, not after the timeout`() {
        var attempts = 0
        val broken =
            WebViewWorkflowParser(Duration.ofSeconds(30)) {
                attempts++
                throw LinkageError("no WebKit")
            }
        val started = System.nanoTime()
        repeat(2) {
            val error = assertFailsWith<IOException> { broken.parse("ci.yml", "on: push\n") }
            assertEquals("The workflow parser could not be started: no WebKit", error.message)
        }
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(10).toNanos(), "no waiting for the timeout")
        assertEquals(2, attempts, "each call tries a new engine")
    }

    @Test
    fun `the parser keeps answering after many calls`() {
        val text = WorkflowFiles.workflow("ci.yml").readText()
        repeat(300) { index ->
            val json = mapper.readTree(parser.parse("ci-$index.yml", text.replace("name: CI", "name: CI $index")))
            assertEquals("CI $index", json.path("name").asString())
        }
    }

    private companion object {
        val parser = WebViewWorkflowParser()
        val mapper: JsonMapper = JsonMapper.builder().build()
    }
}
