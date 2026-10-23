package dev.sluide.githubactions.resolve

import dev.sluide.githubactions.RemoteFile
import dev.sluide.githubactions.RemoteReader
import dev.sluide.githubactions.RemoteText
import dev.sluide.githubactions.RepositoryFiles
import dev.sluide.githubactions.SourceLocation
import dev.sluide.githubactions.SourceLocation.Local
import dev.sluide.githubactions.WorkflowGraph
import dev.sluide.githubactions.WorkflowGraphs
import dev.sluide.githubactions.WorkflowParser
import dev.sluide.githubactions.sampleJson
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where the boxes of a graph come from ([WorkflowGraph.locations]): local and remote files, by Mermaid id. */
class SourceLocationsTest {
    /** Every id of [locations][WorkflowGraph.locations] is a box or subgraph of the Mermaid text. */
    private fun WorkflowGraph.assertIdsDrawn() {
        val drawn =
            mermaid
                .lines()
                .map { it.trim().removePrefix("subgraph ") }
                .map { it.takeWhile { char -> char.isLetterOrDigit() } }
                .toSet()
        for (id in locations.keys) assertTrue(id in drawn, "$id in\n$mermaid")
    }

    @Test
    fun `steps, jobs and black boxes of the workflow, a called workflow and a composite action have their lines`() {
        val fixture = Path.of(checkNotNull(javaClass.getResource("/dev/sluide/githubactions/nested")).toURI())
        val parser = WorkflowParser { fileName, _ -> sampleJson("nested-${fileName.removeSuffix(".yml")}") }

        val graph =
            WorkflowGraphs.of(
                "caller.yml",
                fixture.resolve(".github/workflows/caller.yml").readText(),
                parser,
                RepositoryFiles(fixture),
            )

        val caller = ".github/workflows/caller.yml"
        val build = ".github/workflows/build.yml"
        val setup = ".github/actions/setup/action.yml"
        assertEquals(
            mapOf(
                // The job calling build.yml and the box of the call: the job's line.
                "j0" to Local(caller, 4),
                "j0w" to Local(caller, 4),
                "j0wj0" to Local(build, 8),
                // The composite action's box: the line of its `uses:`; its steps: their lines in action.yml.
                "j0wj0s0" to Local(build, 11),
                "j0wj0s0s0" to Local(setup, 8),
                "j0wj0s0s1" to Local(setup, 11),
                "j0wj0s0s2" to Local(setup, 13),
                "j0wj0s1" to Local(build, 14),
                "j0wj1" to Local(build, 15),
                "j0wj1s0" to Local(build, 20),
                "j1" to Local(caller, 8),
                "j1s0" to Local(caller, 12),
                "j1s1" to Local(caller, 13),
                "j1s2" to Local(caller, 16),
                "j1s3" to Local(caller, 17),
            ),
            graph.locations,
        )
        graph.assertIdsDrawn()
    }

    /** Schema-1 JSON of a workflow `main` (line 3) whose steps (lines 5, 6, …) use [uses]. */
    private fun steps(vararg uses: String): String {
        val steps = uses.withIndex().joinToString(",") { (i, it) -> """{"uses":"$it","line":${5 + i}}""" }
        return """{"schema":1,"errors":[],"events":[],"jobs":[{"id":"main","type":"job","needs":[],"line":3,""" +
            """"runsOn":"ubuntu-latest","steps":[$steps]}]}"""
    }

    private val manifest =
        "name: Set up\nruns:\n  using: composite\n  steps:\n    - run: echo hi\n      shell: bash\n" +
            "    - uses: actions/cache@v4\n"

    private fun draw(
        json: String,
        files: Map<RemoteFile, RemoteText>,
        workflows: Map<String, String> = emptyMap(),
    ): WorkflowGraph =
        WorkflowGraphs.of(
            "main.yml",
            "main",
            { _, text -> workflows[text] ?: json },
            RepositoryFiles(Path.of("/nowhere")),
            RemoteReader { files[it] ?: RemoteText.NotFetched },
        )

    @Test
    fun `steps of a remote composite action link to its web page at the commit read`() {
        val action = RemoteFile("acme", "setup", "v1", "action.yml")
        val web = URI("https://github.com/acme/setup/blob/c0ffee/action.yml")

        val graph =
            draw(
                steps("acme/setup@v1", "acme/other@v2"),
                mapOf(action to RemoteText.Found(manifest, "c0ffee", web)),
            )

        val main = ".github/workflows/main.yml"
        assertEquals(
            mapOf<String, SourceLocation>(
                "j0" to Local(main, 3),
                "j0s0" to Local(main, 5),
                "j0s0s0" to SourceLocation.Remote(5, web),
                "j0s0s1" to SourceLocation.Remote(7, web),
                // Not fetched: the box of the reference has its line.
                "j0s1" to Local(main, 6),
            ),
            graph.locations,
        )
        graph.assertIdsDrawn()
    }

    @Test
    fun `boxes from a remote file without a web page have no location`() {
        val action = RemoteFile("acme", "setup", "v1", "action.yml")

        val graph = draw(steps("acme/setup@v1"), mapOf(action to RemoteText.Found(manifest, "c0ffee")))

        assertTrue("j0s0s0" in graph.mermaid)
        assertEquals(setOf("j0", "j0s0"), graph.locations.keys)
    }

    @Test
    fun `jobs and steps of a remote reusable workflow link to its page`() {
        val called = RemoteFile("acme", "flows", "v1", ".github/workflows/w.yml")
        val web = URI("https://ghes.example.com/acme/flows/blob/beef/.github/workflows/w.yml")
        val json =
            """{"schema":1,"errors":[],"events":[],"jobs":[{"id":"call","type":"reusableWorkflowJob","needs":[],""" +
                """"line":4,"uses":"acme/flows/.github/workflows/w.yml@v1"}]}"""

        val graph =
            draw(
                json,
                mapOf(called to RemoteText.Found("remote", "beef", web)),
                mapOf("remote" to steps("actions/checkout@v6")),
            )

        val main = ".github/workflows/main.yml"
        assertEquals(
            mapOf<String, SourceLocation>(
                "j0" to Local(main, 4),
                "j0w" to Local(main, 4),
                "j0wj0" to SourceLocation.Remote(3, web),
                "j0wj0s0" to SourceLocation.Remote(5, web),
            ),
            graph.locations,
        )
        graph.assertIdsDrawn()
    }
}
