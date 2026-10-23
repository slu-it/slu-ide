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
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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

    /** [text] as [path] in [repository] at [ref], fetched as of [asOf] (null: a commit SHA). */
    private fun found(
        repository: String,
        ref: String,
        path: String,
        text: String,
        asOf: Instant? = AS_OF,
    ) {
        files[file(repository, ref, path)] = RemoteText.Found(text.trimIndent(), "c0ffee", asOf)
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
        zone: ZoneId = ZoneOffset.UTC,
        maxNodes: Int = WorkflowDrawing.MAX_NODES,
    ): WorkflowGraph {
        val workflow = assertIs<WorkflowJson.Result.Decoded>(WorkflowJson.decode(json)).workflow
        return WorkflowDrawing.draw(
            workflow,
            maxNodes = maxNodes,
            resolver = ReferenceResolver(sources, parser, cancelled, remote, zone),
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
        found("acme/node", SHA, "action.yml", "runs:\n  using: node20", asOf = null)
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
                    subgraph j0s0 ["acme/setup@v1 (composite) #183; as of 2026-10-20"]
                        direction TB
                        j0s0s0["run: echo hi"]
                        j0s0s1["./scripts/local<br/>workspace-relative"]
                        j0s0s2["acme/node@${SHA.dropLast(3)}#8230;<br/>JS action (node20)"]
                        j0s0s0 --> j0s0s1
                        j0s0s1 --> j0s0s2
                    end
                    j0s1["acme/tools/lint@main<br/>Docker (Dockerfile)<br/>inputs: level#42;<br/>as of 2026-10-20"]
                    j0s0 --> j0s1
                end
                classDef blackbox stroke-width:3px
                classDef unresolved stroke-dasharray: 5 5
                classDef failed stroke:#d9534f,stroke-width:2px
                classDef cycle stroke:#f0ad4e,stroke-width:2px,stroke-dasharray: 2 2
                classDef limit stroke-dasharray: 1 4
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
        assertTrue("subgraph j0w [\"calls build.yml #183; as of 2026-10-20\"]" in graph.mermaid, graph.mermaid)
        assertTrue("subgraph j0wj0w [\"calls inner.yml #183; as of 2026-10-20\"]" in graph.mermaid, graph.mermaid)
        assertEquals("j0wj0wj0s0[\"./local<br/>workspace-relative\"]", graph.box("j0wj0wj0s0"))
        assertEquals(
            "j0wj0wj0s1[\"acme/flows@v2<br/>JS action (node24)<br/>as of 2026-10-20\"]",
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

        assertEquals("j0w[\"acme/new/.github/workflows/x.yml@v1<br/>not fetched\"]", graph.box("j0w"))
        assertEquals("j1w[\"acme/gone/.github/workflows/x.yml@v1<br/>not found\"]", graph.box("j1w"))
        assertEquals("j2s0[\"acme/new@v1<br/>not fetched\"]", graph.box("j2s0"))
        assertEquals("j2s1[\"acme/new@v1<br/>not fetched\"]", graph.box("j2s1"))
        assertEquals("j2s2[\"acme/gone@v1<br/>not found (no action.yml)\"]", graph.box("j2s2"))
        assertEquals("j2s3[\"acme/broken@v1<br/>Repository not found\"]", graph.box("j2s3"))
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
    fun `the date it was fetched is the day in the time zone given`() {
        found("acme/a", "v1", "action.yml", "runs:\n  using: node20")

        val graph = draw(steps("acme/a@v1"), zone = ZoneId.of("Pacific/Kiritimati"))

        assertTrue(graph.box("j0s0").endsWith("<br/>as of 2026-10-21\"]"), graph.box("j0s0"))
    }

    @Test
    fun `a reader that throws fails the reference with its message`() {
        val graph = draw(steps("acme/x@v1"), remote = { error("network down") })

        assertEquals("j0s0[\"acme/x@v1<br/>network down\"]", graph.box("j0s0"))
        assertEquals(listOf("j0s0"), graph.styled("failed"))
    }

    @Test
    fun `paths are confined to the remote repository`() {
        found("acme/repo", "v1", "x/action.yml", "runs:\n  using: node20")

        val graph = draw(steps("acme/repo/../../x@v1", "acme/repo/sub/../x@v1"))

        assertEquals("j0s0[\"acme/repo/../../x@v1<br/>outside the repository\"]", graph.box("j0s0"))
        assertEquals(listOf("j0s0"), graph.styled("failed"))
        assertTrue("JS action (node20)" in graph.box("j0s1"), graph.box("j0s1"))
        assertEquals(listOf(file("acme/repo", "v1", "x/action.yml")), reads)
    }

    @Test
    fun `an action using one that uses it again is a cycle across repositories`() {
        found("acme/a", "v1", "action.yml", "runs:\n  using: composite\n  steps:\n    - uses: acme/b@v1")
        found("acme/b", "v1", "action.yml", "runs:\n  using: composite\n  steps:\n    - uses: acme/a@v1")

        val graph = draw(steps("acme/a@v1"))

        assertTrue("subgraph j0s0s0 [\"acme/b@v1 (composite) #183; as of 2026-10-20\"]" in graph.mermaid)
        assertEquals("j0s0s0s0[\"acme/a@v1<br/>cycle: back to main\"]", graph.box("j0s0s0s0"))
        assertEquals(listOf("j0s0s0s0"), graph.styled("cycle"))
    }

    @Test
    fun `a long reference does not cut off when it was fetched`() {
        found("acme/set-up-the-whole-toolchain", "v1", "action.yml", "runs:\n  using: composite\n  steps: []")

        val graph = draw(steps("acme/set-up-the-whole-toolchain@v1"))

        val title = graph.mermaid.lines().single { "subgraph j0s0 " in it }
        assertTrue(title.endsWith(" #183; as of 2026-10-20\"]"), title)
    }

    @Test
    fun `the same path in another ref or repository is another file`() {
        found("acme/a", "v1", "action.yml", "runs:\n  using: composite\n  steps:\n    - uses: acme/a@v2")
        found("acme/a", "v2", "action.yml", "runs:\n  using: node20")

        val graph = draw(steps("acme/a@v1"))

        assertTrue("JS action (node20)" in graph.box("j0s0s0"), graph.mermaid)
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

        assertEquals("j0s0[\"acme/a@v1\"]", graph.box("j0s0"))
        assertTrue("JS action (node20)" in graph.box("j0s1"))
        assertEquals(listOf("j0s0", "j0s1"), graph.styled("blackbox"))
        assertEquals(0, graph.unfetched)
        assertEquals(1, graph.remoteReferences)
        assertNull(graph.note)
    }

    @Test
    fun `with a reader but no repository, local references stay black boxes`() {
        val graph = draw(steps("./local"))

        assertEquals("j0s0[\"./local\"]", graph.box("j0s0"))
        assertTrue(reads.isEmpty())
    }

    private companion object {
        /** Noon UTC: 2026-10-20 in the tests' zone, UTC. */
        val AS_OF: Instant = Instant.parse("2026-10-20T12:00:00Z")

        const val SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
