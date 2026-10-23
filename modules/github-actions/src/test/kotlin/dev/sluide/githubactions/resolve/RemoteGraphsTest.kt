package dev.sluide.githubactions.resolve

import dev.sluide.githubactions.RemoteFile
import dev.sluide.githubactions.RemoteReader
import dev.sluide.githubactions.RemoteText
import dev.sluide.githubactions.RepositoryFiles
import dev.sluide.githubactions.WorkflowGraph
import dev.sluide.githubactions.WorkflowGraphs
import dev.sluide.githubactions.WorkflowParser
import dev.sluide.githubactions.WorkflowSources
import dev.sluide.githubactions.mermaid.WorkflowDrawing
import dev.sluide.githubactions.model.WorkflowJson
import dev.sluide.githubactions.resolve.NestedGraphsTest.Companion.calls
import dev.sluide.githubactions.resolve.NestedGraphsTest.Companion.steps
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Remote reusable workflows and actions read through a fake [RemoteReader] and drawn nested. */
class RemoteGraphsTest {
    /** The parser's JSON by the text it is given; every workflow's text is a key here. */
    private val workflows = mutableMapOf<String, String>()
    private val parsed = mutableListOf<String>()
    private val parser =
        WorkflowParser { _, text ->
            parsed += text
            workflows[text] ?: error("boom")
        }

    /** What the reader has; anything else was not fetched. */
    private val files = mutableMapOf<RemoteFile, RemoteText>()
    private val reads = mutableListOf<RemoteFile>()
    private val reader =
        RemoteReader { file ->
            reads += file
            files[file] ?: RemoteText.NotFetched
        }

    private fun file(
        repository: String,
        ref: String,
        path: String,
    ): RemoteFile {
        val (owner, repo) = repository.split('/')
        return RemoteFile(owner, repo, ref, path)
    }

    /** [text] as [path] in [repository] at [ref]. */
    private fun found(
        repository: String,
        ref: String,
        path: String,
        text: String,
    ) {
        files[file(repository, ref, path)] = RemoteText.Found(text.trimIndent(), "c0ffee")
    }

    /** A remote workflow file whose parser JSON is [json]. */
    private fun remoteWorkflow(
        repository: String,
        ref: String,
        path: String,
        json: String,
    ) {
        val text = "# $repository/$path@$ref"
        workflows[text] = json
        found(repository, ref, path, text)
    }

    private fun draw(
        json: String,
        sources: WorkflowSources = WorkflowSources.NONE,
        remote: RemoteReader? = reader,
        cancelled: () -> Boolean = { false },
        maxNodes: Int = WorkflowDrawing.MAX_NODES,
    ): WorkflowGraph {
        val workflow = assertIs<WorkflowJson.Result.Decoded>(WorkflowJson.decode(json)).workflow
        return WorkflowDrawing.draw(
            workflow,
            maxNodes = maxNodes,
            resolver = ReferenceResolver(sources, parser, cancelled, remote),
            path = ".github/workflows/main.yml",
        )
    }

    /** The `class … <style>` line's ids. */
    private fun WorkflowGraph.styled(style: String): List<String> =
        mermaid
            .lines()
            .singleOrNull { it.startsWith("    class ") && it.endsWith(" $style") }
            ?.removePrefix("    class ")
            ?.removeSuffix(" $style")
            ?.split(",") ?: emptyList()

    /** The line drawing the box [id]. */
    private fun WorkflowGraph.box(id: String): String = mermaid.lines().single { it.trim().startsWith("$id[") }.trim()

    @Test
    fun `a remote composite action is drawn nested, JS and Docker actions annotated, with when they were fetched`() {
        found(
            "acme/setup",
            "v1",
            "action.yml",
            """
            name: Set up
            runs:
              using: composite
              steps:
                - run: echo hi
                  shell: bash
                - uses: ./scripts/local
                - uses: acme/node@$SHA
            """,
        )
        found("acme/node", SHA, "action.yml", "runs:\n  using: node20")
        found(
            "acme/tools",
            "main",
            "lint/action.yaml",
            "inputs:\n  level: {required: true}\nruns:\n  using: docker\n  image: Dockerfile",
        )
        files[file("acme/tools", "main", "lint/action.yml")] = RemoteText.Missing

        val graph = draw(steps("acme/setup@v1", "acme/tools/lint@main"))

        assertNull(graph.note)
        assertEquals(0, graph.unfetched)
        assertEquals(3, graph.remoteReferences)
        assertEquals(
            """
            flowchart LR
                subgraph j0 ["main #183; ubuntu-latest"]
                    direction TB
                    subgraph j0s0 ["<b>acme/setup@v1</b> #183; <i>#60;composite#62;</i>"]
                        direction TB
                        j0s0s0["run: echo hi"]
                        j0s0s1["<b>./scripts/local</b><br/>workspace-relative"]
                        j0s0s2["<b>acme/node@${SHA.dropLast(3)}#8230;</b><br/><i>#60;JS action (node20)#62;</i>"]
                        j0s0s0 --> j0s0s1
                        j0s0s1 --> j0s0s2
                    end
                    j0s1["<b>acme/tools/lint@main</b><br/><i>#60;Docker (Dockerfile)#62;</i>"]
                    j0s0 --> j0s1
                end
                classDef blackbox stroke-width:3px
                classDef unresolved stroke-dasharray: 5 5
                classDef failed stroke:#d9534f,stroke-width:2px
                classDef cycle stroke:#f0ad4e,stroke-width:2px,stroke-dasharray: 2 2
                classDef limit stroke-dasharray: 1 4
                classDef inputs stroke-dasharray: 4 3,stroke-width:1px
                classDef spacer fill:none,stroke:none
                class j0s0s1,j0s0s2,j0s1 blackbox

            """.trimIndent(),
            graph.mermaid,
        )
        assertEquals(
            listOf(
                file("acme/setup", "v1", "action.yml"),
                file("acme/node", SHA, "action.yml"),
                file("acme/tools", "main", "lint/action.yml"),
                file("acme/tools", "main", "lint/action.yaml"),
            ),
            reads,
        )
    }

    @Test
    fun `a remote reusable workflow is drawn nested, its own local workflows read from the same repository and ref`() {
        remoteWorkflow("acme/flows", "v2", ".github/workflows/build.yml", calls("./.github/workflows/inner.yml"))
        remoteWorkflow("acme/flows", "v2", ".github/workflows/inner.yml", steps("./local", "acme/flows@v2"))
        found("acme/flows", "v2", "action.yml", "runs:\n  using: node24")

        val graph = draw(calls("acme/flows/.github/workflows/build.yml@v2"))

        assertNull(graph.note)
        assertTrue("subgraph j0w [\"calls <b>build.yml</b>\"]" in graph.mermaid, graph.mermaid)
        assertTrue("subgraph j0wj0w [\"calls <b>inner.yml</b>\"]" in graph.mermaid, graph.mermaid)
        assertEquals("j0wj0wj0s0[\"<b>./local</b><br/>workspace-relative\"]", graph.box("j0wj0wj0s0"))
        assertEquals(
            "j0wj0wj0s1[\"<b>acme/flows@v2</b><br/><i>#60;JS action (node24)#62;</i>\"]",
            graph.box("j0wj0wj0s1"),
        )
        assertEquals(
            listOf(
                file("acme/flows", "v2", ".github/workflows/build.yml"),
                file("acme/flows", "v2", ".github/workflows/inner.yml"),
                file("acme/flows", "v2", "action.yml"),
            ),
            reads,
        )
    }

    @Test
    fun `references not fetched, missing or failing say so, each file not fetched is counted once`() {
        files[file("acme/gone", "v1", ".github/workflows/x.yml")] = RemoteText.Missing
        files[file("acme/gone", "v1", "action.yml")] = RemoteText.Missing
        files[file("acme/gone", "v1", "action.yaml")] = RemoteText.Missing
        files[file("acme/broken", "v1", "action.yml")] = RemoteText.Failed("Repository not found.\nfatal: more")
        val json =
            """{"schema":1,"errors":[],"events":[],"jobs":[""" +
                """{"id":"a","type":"reusableWorkflowJob","needs":[],"uses":"acme/new/.github/workflows/x.yml@v1"},""" +
                """{"id":"b","type":"reusableWorkflowJob","needs":[],""" +
                """"uses":"acme/gone/.github/workflows/x.yml@v1"},""" +
                """{"id":"c","type":"job","needs":[],"steps":[{"uses":"acme/new@v1"},{"uses":"acme/new@v1"},""" +
                """{"uses":"acme/gone@v1"},{"uses":"acme/broken@v1"}]}]}"""

        val graph = draw(json)

        assertEquals("j0w[\"<b>acme/new/.github/workflows/x.yml@v1</b><br/>not fetched\"]", graph.box("j0w"))
        assertEquals("j1w[\"<b>acme/gone/.github/workflows/x.yml@v1</b><br/>not found\"]", graph.box("j1w"))
        assertEquals("j2s0[\"<b>acme/new@v1</b><br/>not fetched\"]", graph.box("j2s0"))
        assertEquals("j2s1[\"<b>acme/new@v1</b><br/>not fetched\"]", graph.box("j2s1"))
        assertEquals("j2s2[\"<b>acme/gone@v1</b><br/>not found (no action.yml)\"]", graph.box("j2s2"))
        assertEquals("j2s3[\"<b>acme/broken@v1</b><br/>Repository not found\"]", graph.box("j2s3"))
        assertEquals(listOf("j0w", "j1w", "j2s0", "j2s1", "j2s2"), graph.styled("unresolved"))
        assertEquals(listOf("j2s3"), graph.styled("failed"))
        assertEquals(2, graph.unfetched)
        assertEquals(6, graph.remoteReferences)
        assertEquals("2 references not fetched — Fetch References to show them.", graph.note)
        assertEquals(1, reads.count { it == file("acme/new", "v1", "action.yml") }, "read once per graph")
    }

    @Test
    fun `WorkflowGraphs reads remote references with the reader it is given, one not fetched`() {
        workflows["main"] = steps("acme/new@v1")

        val graph = WorkflowGraphs.of("main.yml", "main", parser, WorkflowSources.NONE, reader)

        assertEquals("1 reference not fetched — Fetch References to show it.", graph.note)
        assertEquals(listOf(file("acme/new", "v1", "action.yml")), reads)
    }

    @Test
    fun `only the remote references drawn are counted`() {
        val graph = draw(steps("acme/a@v1", "acme/b@v1", "acme/c@v1"), maxNodes = 2)

        assertEquals(2, graph.remoteReferences)
        assertEquals(2, graph.unfetched)
    }

    @Test
    fun `a branch ref shows no fetch date`() {
        found("acme/a", "v1", "action.yml", "runs:\n  using: node20")

        val graph = draw(steps("acme/a@v1"))

        assertTrue("as of" !in graph.mermaid, graph.mermaid)
        assertTrue(graph.box("j0s0").contains("#60;JS action (node20)#62;"), graph.box("j0s0"))
    }

    @Test
    fun `a reader that throws fails the reference with its message`() {
        val graph = draw(steps("acme/x@v1"), remote = { error("network down") })

        assertEquals("j0s0[\"<b>acme/x@v1</b><br/>network down\"]", graph.box("j0s0"))
        assertEquals(listOf("j0s0"), graph.styled("failed"))
    }

    @Test
    fun `paths are confined to the remote repository`() {
        found("acme/repo", "v1", "x/action.yml", "runs:\n  using: node20")

        val graph = draw(steps("acme/repo/../../x@v1", "acme/repo/sub/../x@v1"))

        assertEquals("j0s0[\"<b>acme/repo/../../x@v1</b><br/>outside the repository\"]", graph.box("j0s0"))
        assertEquals(listOf("j0s0"), graph.styled("failed"))
        assertTrue("#60;JS action (node20)#62;" in graph.box("j0s1"), graph.box("j0s1"))
        assertEquals(listOf(file("acme/repo", "v1", "x/action.yml")), reads)
    }

    @Test
    fun `an action using one that uses it again is a cycle across repositories`() {
        found("acme/a", "v1", "action.yml", "runs:\n  using: composite\n  steps:\n    - uses: acme/b@v1")
        found("acme/b", "v1", "action.yml", "runs:\n  using: composite\n  steps:\n    - uses: acme/a@v1")

        val graph = draw(steps("acme/a@v1"))

        assertTrue("subgraph j0s0s0 [\"<b>acme/b@v1</b> #183; <i>#60;composite#62;</i>\"]" in graph.mermaid)
        assertEquals("j0s0s0s0[\"<b>acme/a@v1</b><br/>cycle: back to main\"]", graph.box("j0s0s0s0"))
        assertEquals(listOf("j0s0s0s0"), graph.styled("cycle"))
    }

    @Test
    fun `the same path in another ref or repository is another file`() {
        found("acme/a", "v1", "action.yml", "runs:\n  using: composite\n  steps:\n    - uses: acme/a@v2")
        found("acme/a", "v2", "action.yml", "runs:\n  using: node20")

        val graph = draw(steps("acme/a@v1"))

        assertTrue("#60;JS action (node20)#62;" in graph.box("j0s0s0"), graph.mermaid)
    }

    @Test
    fun `a remote workflow's problems go into the note, its parser failure fails it like a local one`() {
        remoteWorkflow("acme/flows", "v1", ".github/workflows/a.yml", calls("acme/x/.github/workflows/y.yml@v1", 2))
        found("acme/flows", "v1", ".github/workflows/broken.yml", "not in the parser's map")
        val json =
            """{"schema":1,"errors":[],"events":[],"jobs":[""" +
                """{"id":"a","type":"reusableWorkflowJob","needs":[],""" +
                """"uses":"acme/flows/.github/workflows/a.yml@v1"},""" +
                """{"id":"b","type":"reusableWorkflowJob","needs":[],""" +
                """"uses":"acme/flows/.github/workflows/broken.yml@v1"}]}"""

        val graph = draw(json)

        assertTrue("<br/>parser failed: boom\"]" in graph.box("j1w"), graph.box("j1w"))
        assertEquals(
            "Called workflows with problems: acme/flows/.github/workflows/a.yml@v1. " +
                "1 reference not fetched — Fetch References to show it.",
            graph.note,
        )
    }

    @Test
    fun `once the graph is no longer wanted no remote file is read`() {
        val graph = draw(steps("acme/a@v1"), cancelled = { true })

        assertTrue(reads.isEmpty())
        assertTrue("not expanded (cancelled)" in graph.box("j0s0"))
    }

    @Test
    fun `without a reader remote references stay black boxes, local ones are still followed`(
        @TempDir repo: Path,
    ) {
        repo
            .resolve("local")
            .createDirectories()
            .resolve("action.yml")
            .writeText("runs:\n  using: node20")

        val graph = draw(steps("acme/a@v1", "./local"), RepositoryFiles(repo), remote = null)

        assertEquals("j0s0[\"<b>acme/a@v1</b>\"]", graph.box("j0s0"))
        assertTrue("#60;JS action (node20)#62;" in graph.box("j0s1"))
        assertEquals(listOf("j0s0", "j0s1"), graph.styled("blackbox"))
        assertEquals(0, graph.unfetched)
        assertEquals(1, graph.remoteReferences)
        assertNull(graph.note)
    }

    @Test
    fun `with a reader but no repository, local references stay black boxes`() {
        val graph = draw(steps("./local"))

        assertEquals("j0s0[\"<b>./local</b>\"]", graph.box("j0s0"))
        assertTrue(reads.isEmpty())
    }

    private companion object {
        const val SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
