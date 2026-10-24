package dev.sluide.githubactions.resolve

import dev.sluide.githubactions.RepositoryFiles
import dev.sluide.githubactions.SAFE_LABEL
import dev.sluide.githubactions.WorkflowGraph
import dev.sluide.githubactions.WorkflowGraphs
import dev.sluide.githubactions.WorkflowParser
import dev.sluide.githubactions.WorkflowSources
import dev.sluide.githubactions.box
import dev.sluide.githubactions.mermaid.WorkflowDrawing
import dev.sluide.githubactions.model.WorkflowJson
import dev.sluide.githubactions.sampleJson
import dev.sluide.githubactions.styled
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Local reusable workflows and actions drawn nested, from a repository in a temporary folder. */
class NestedGraphsTest {
    @TempDir
    lateinit var dir: Path

    private val repo by lazy { dir.resolve("repo").createDirectories() }

    /** The parser's JSON by file name; every workflow file read is parsed by name, its text does not matter. */
    private val workflows = mutableMapOf<String, String>()
    private val parsed = mutableListOf<String>()
    private val parser =
        WorkflowParser { fileName, _ ->
            parsed += fileName
            workflows[fileName] ?: error("boom")
        }

    private fun workflow(
        name: String,
        json: String,
    ) {
        workflows[name] = json
        repo
            .resolve(".github/workflows")
            .createDirectories()
            .resolve(name)
            .writeText("# $name")
    }

    private fun action(
        path: String,
        text: String,
        fileName: String = "action.yml",
    ) {
        repo
            .resolve(path)
            .createDirectories()
            .resolve(fileName)
            .writeText(text.trimIndent())
    }

    private fun draw(name: String): WorkflowGraph = WorkflowGraphs.of(name, "", parser, RepositoryFiles(repo))

    @Test
    fun `a reusable workflow and composite actions are drawn nested, a second use refers to the first`() {
        val fixture = Path.of(checkNotNull(javaClass.getResource("/dev/sluide/githubactions/nested")).toURI())
        val parser = WorkflowParser { fileName, _ -> sampleJson("nested-${fileName.removeSuffix(".yml")}") }

        val graph =
            WorkflowGraphs.of(
                "caller.yml",
                fixture.resolve(".github/workflows/caller.yml").readText(),
                parser,
                RepositoryFiles(fixture),
            )

        assertNull(graph.note)
        assertEquals(
            """
            flowchart LR
                subgraph triggers ["Triggers"]
                    direction LR
                    t0(["push"])
                end
                subgraph j0 ["build #183; calls <b>./.github/workflows/build.yml</b>"]
                    direction TB
                    subgraph j0w ["calls <b>build.yml</b>"]
                        direction TB
                        j0wi("java: 25")
                        subgraph j0wj0 ["compile #183; ubuntu-latest"]
                            direction TB
                            subgraph j0wj0s0 ["<b>Set up Java</b> #183; <i>#60;composite#62;</i>"]
                                direction TB
                                j0wj0s0s0["<b>actions/setup-java@v5</b><div class='sluide-inputs'>java-version: #36;#123;#123; inputs.java #125;#125;</div>"]
                                j0wj0s0s1["<b>./.github/actions/greet</b><br/><i>#60;JS action (node20)#62;</i><br/>if: runner.os #61;#61; #39;Linux#39;"]
                                j0wj0s0s2["Show version"]
                                j0wj0s0s0 --> j0wj0s0s1
                                j0wj0s0s1 --> j0wj0s0s2
                            end
                            j0wj0s1["run: ./gradlew assemble"]
                            j0wj0s0 --> j0wj0s1
                        end
                        subgraph j0wj1 ["package #183; ubuntu-latest"]
                            direction TB
                            j0wj1s0["run: ./gradlew bootJar"]
                        end
                        j0wi ~~~ j0wj0
                        j0wj0 -.->|"if: github.ref #61;#61; #39;refs/heads/main#39;"| j0wj1
                    end
                end
                subgraph j1 ["test #183; ubuntu-latest"]
                    direction TB
                    j1s0["<b>actions/checkout@v6</b>"]
                    j1s1["<b>./.github/actions/setup</b><br/>expanded in build / compile"]
                    j1s2["<b>./.github/actions/missing</b><br/>not found (no action.yml)"]
                    j1s3["run: ./gradlew test"]
                    j1s0 --> j1s1
                    j1s1 --> j1s2
                    j1s2 --> j1s3
                end
                triggers --> j0
                j0 --> j1
                classDef blackbox stroke-width:3px
                classDef unresolved stroke-dasharray: 5 5
                classDef failed stroke:#d9534f,stroke-width:2px
                classDef cycle stroke:#f0ad4e,stroke-width:2px,stroke-dasharray: 2 2
                classDef limit stroke-dasharray: 1 4
                classDef inputs stroke-dasharray: 4 3,stroke-width:1px
                class j0wj0s0s0,j0wj0s0s1,j1s0,j1s1 blackbox
                class j1s2 unresolved
                class j0wi inputs

            """.trimIndent(),
            graph.mermaid,
        )
    }

    @Test
    fun `without a repository local references stay black boxes`() {
        workflow("a.yml", calls("./.github/workflows/b.yml"))

        val graph = WorkflowGraphs.of("a.yml", "", parser, WorkflowSources.NONE)

        assertEquals(listOf("a.yml"), parsed)
        assertEquals(listOf("j0w"), graph.styled("blackbox"))
    }

    @Test
    fun `a workflow calling back into one being expanded is a cycle`() {
        workflow("a.yml", calls("./.github/workflows/b.yml"))
        workflow("b.yml", calls("./.github/workflows/a.yml"))

        val graph = draw("a.yml")

        assertEquals(listOf("a.yml", "b.yml"), parsed)
        assertTrue("subgraph j0w [\"calls <b>b.yml</b>\"]" in graph.mermaid, graph.mermaid)
        assertEquals(listOf("j0wj0w"), graph.styled("cycle"))
        assertEquals(
            "j0wj0w[\"<b>./.github/workflows/a.yml</b><br/>cycle: back to this workflow\"]",
            graph.box("j0wj0w"),
        )
    }

    @Test
    fun `a composite action using itself is a cycle, however its path is written`() {
        action(
            "x",
            """
            runs:
              using: composite
              steps:
                - uses: ./y/../x/
            """,
        )
        workflow("main.yml", steps("./x"))

        val graph = draw("main.yml")

        assertTrue("subgraph j0s0 [\"<b>./x</b> #183; <i>#60;composite#62;</i>\"]" in graph.mermaid, graph.mermaid)
        assertEquals("j0s0s0[\"<b>./y/../x/</b><br/>cycle: back to main\"]", graph.box("j0s0s0"))
        assertEquals(listOf("j0s0s0"), graph.styled("cycle"))
    }

    @Test
    fun `at most ten expansions nest`() {
        for (level in 0..11) workflow("w$level.yml", calls("./.github/workflows/w${level + 1}.yml"))

        val graph = draw("w0.yml")

        assertEquals((0..10).map { "w$it.yml" }, parsed)
        assertTrue("[\"calls <b>w10.yml</b>\"]" in graph.mermaid)
        assertFalse("calls <b>w11.yml</b>\"]" in graph.mermaid)
        val deepest = "j0w".repeat(11)
        assertEquals(listOf(deepest), graph.styled("limit"))
        assertTrue("not expanded (depth limit)" in graph.box(deepest))
    }

    @Test
    fun `at most 50 files are read for a graph`() {
        for (index in 0 until 30) action("a$index", "runs:\n  using: node20")
        // Each missing one is tried as action.yml and action.yaml.
        val uses = (0 until 30).map { "./a$it" } + (0 until 30).map { "./missing$it" }
        workflow("main.yml", steps(*uses.toTypedArray()))

        val graph = draw("main.yml")

        assertEquals(30, Regex("JS action").findAll(graph.mermaid).count())
        assertEquals(10, Regex("not found").findAll(graph.mermaid).count())
        assertEquals(20, graph.styled("limit").size)
        assertTrue("not expanded (file limit)" in graph.box("j0s59"))
        assertEquals("Read at most 50 referenced files.", graph.note)
    }

    @Test
    fun `a file expanded once says where elsewhere, black boxes repeat what they are`() {
        workflow(
            "main.yml",
            """{"schema":1,"errors":[],"events":[],"jobs":[""" +
                """{"id":"a","type":"reusableWorkflowJob","needs":[],"uses":"./.github/workflows/b.yml"},""" +
                """{"id":"b","type":"reusableWorkflowJob","needs":[],"uses":"./.github/workflows/b.yml"},""" +
                """{"id":"c","type":"job","needs":[],"steps":[{"uses":"./d"},{"uses":"./d"}]}]}""",
        )
        workflow("b.yml", steps("actions/checkout@v6"))
        action(
            "d",
            """
            inputs:
              image: {required: true}
            runs:
              using: docker
              image: Dockerfile
            """,
            fileName = "action.yaml",
        )

        val graph = draw("main.yml")

        assertEquals(listOf("main.yml", "b.yml"), parsed)
        assertTrue("subgraph j0w [\"calls <b>b.yml</b>\"]" in graph.mermaid)
        assertEquals(
            "j1w[\"<b>./.github/workflows/b.yml</b><br/>expanded in a\"]",
            graph.box("j1w"),
        )
        for (id in listOf("j2s0", "j2s1")) {
            assertEquals("$id[\"<b>./d</b><br/><i>#60;Docker (Dockerfile)#62;</i>\"]", graph.box(id))
        }
        assertEquals(listOf("j0wj0s0", "j1w", "j2s0", "j2s1"), graph.styled("blackbox"))
    }

    @Test
    fun `targets outside the repository, unreadable or broken ones fail with a reason`() {
        dir
            .resolve("outside")
            .createDirectories()
            .resolve("action.yml")
            .writeText("runs:\n  using: node20")
        Files.createSymbolicLink(repo.resolve("link"), dir.resolve("outside"))
        action("broken", "runs: [")
        action("python", "runs:\n  using: python")
        workflow("throws.yml", "")
        workflows.remove("throws.yml")
        workflow("after.yml", steps("./x"))
        action("nouse", "name: x")
        action("noimage", "runs:\n  using: docker")
        workflow(
            "empty.yml",
            """{"schema":1,"errors":[{"message":"Unexpected value 'x'.\nMore","line":2}],"jobs":[]}""",
        )
        workflow(
            "main.yml",
            """{"schema":1,"errors":[],"events":[],"jobs":[""" +
                """{"id":"a","type":"reusableWorkflowJob","needs":[],"uses":"./.github/workflows/empty.yml"},""" +
                """{"id":"b","type":"reusableWorkflowJob","needs":[],"uses":"./.github/workflows/throws.yml"},""" +
                """{"id":"c","type":"reusableWorkflowJob","needs":[],"uses":"./.github/workflows/after.yml"},""" +
                """{"id":"d","type":"job","needs":[],"steps":[""" +
                """{"uses":"./../outside"},{"uses":"./link"},{"uses":"./broken"},{"uses":"./python"},""" +
                """{"uses":"./nouse"},{"uses":"./noimage"}]}]}""",
        )

        val graph = draw("main.yml")

        assertNull(graph.note)
        assertEquals(listOf("main.yml", "empty.yml", "throws.yml"), parsed, "not called again after it threw")
        assertEquals(
            listOf("j0w", "j1w", "j2w", "j3s0", "j3s1", "j3s2", "j3s3", "j3s4"),
            graph.styled("failed"),
        )
        assertTrue("<br/>Unexpected value #39;x#39;\"]" in graph.box("j0w"), graph.box("j0w"))
        assertTrue("<br/>parser failed: boom\"]" in graph.box("j1w"), graph.box("j1w"))
        assertTrue("<br/>parser unavailable\"]" in graph.box("j2w"), graph.box("j2w"))
        assertEquals("j3s0[\"<b>./../outside</b><br/>outside the repository\"]", graph.box("j3s0"))
        assertEquals("j3s1[\"<b>./link</b><br/>outside the repository\"]", graph.box("j3s1"))
        assertTrue("<b>./broken</b><br/>line 1: " in graph.box("j3s2"), graph.box("j3s2"))
        assertEquals("j3s3[\"<b>./python</b><br/>unknown runs.using: python\"]", graph.box("j3s3"))
        assertEquals("j3s4[\"<b>./nouse</b><br/>no runs.using\"]", graph.box("j3s4"))
        assertEquals("j3s5[\"<b>./noimage</b><br/><i>#60;Docker (no image)#62;</i>\"]", graph.box("j3s5"))
    }

    @Test
    fun `a nested parser or source that is interrupted restores the interrupt flag`() {
        workflow("b.yml", steps("actions/checkout@v6"))
        workflow("main.yml", calls("./.github/workflows/b.yml"))
        val interrupting =
            WorkflowParser { fileName, _ ->
                if (fileName == "b.yml") throw InterruptedException() else workflows.getValue(fileName)
            }

        val parserGraph = WorkflowGraphs.of("main.yml", "", interrupting, RepositoryFiles(repo))

        try {
            assertTrue(Thread.interrupted(), "parser: interrupt flag restored (and cleared here)")
            assertTrue("parser failed: " in parserGraph.box("j0w"), parserGraph.box("j0w"))

            val interrupted =
                object : WorkflowSources {
                    override fun local(path: String): String =
                        if (path.endsWith("b.yml")) throw InterruptedException() else "# main"
                }
            val sourceGraph = WorkflowGraphs.of("main.yml", "", parser, interrupted)

            assertTrue(Thread.interrupted(), "source: interrupt flag restored (and cleared here)")
            assertTrue("<br/>unreadable\"]" in sourceGraph.box("j0w"), sourceGraph.box("j0w"))
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `called workflows with unsupported output or without jobs fail with a reason`() {
        workflow("v2.yml", """{"schema":2}""")
        workflow("nojobs.yml", """{"schema":1,"jobs":[]}""")
        workflow(
            "main.yml",
            """{"schema":1,"errors":[],"events":[],"jobs":[""" +
                """{"id":"a","type":"reusableWorkflowJob","needs":[],"uses":"./.github/workflows/v2.yml"},""" +
                """{"id":"b","type":"reusableWorkflowJob","needs":[],"uses":"./.github/workflows/nojobs.yml"}]}""",
        )

        val graph = draw("main.yml")

        assertTrue("<br/>Unsupported parser output (schema 2, expected 1)\"]" in graph.box("j0w"), graph.box("j0w"))
        assertTrue("<br/>no jobs\"]" in graph.box("j1w"), graph.box("j1w"))
        assertEquals(listOf("j0w", "j1w"), graph.styled("failed"))
    }

    @Test
    fun `a file the system refuses names the reason, not its path`() {
        workflow("main.yml", steps("./locked"))
        val refusing =
            object : WorkflowSources {
                override fun local(path: String): String = throw AccessDeniedException("/repo/$path")
            }

        val graph = WorkflowGraphs.of("main.yml", "", parser, refusing)

        assertEquals("j0s0[\"<b>./locked</b><br/>unreadable (AccessDeniedException)\"]", graph.box("j0s0"))
    }

    @Test
    fun `once the graph is no longer wanted nothing more is read or parsed`() {
        workflow("main.yml", calls("./.github/workflows/b.yml"))
        workflow("b.yml", steps("actions/checkout@v6"))

        val graph = WorkflowGraphs.of("main.yml", "", parser, RepositoryFiles(repo)) { parsed.isNotEmpty() }

        assertEquals(listOf("main.yml"), parsed)
        assertTrue("not expanded (cancelled)" in graph.box("j0w"), graph.box("j0w"))
    }

    @Test
    fun `a called workflow's problems go into the note`() {
        workflow("main.yml", calls("./.github/workflows/b.yml"))
        workflow("b.yml", calls("actions/x/.github/workflows/y.yml@v1", problems = 2))

        assertEquals("Called workflows with problems: ./.github/workflows/b.yml.", draw("main.yml").note)
    }

    @Test
    fun `an expansion that does not fit stays a box, later ones are still tried`() {
        val composite = (1..6).joinToString("\n") { "    - run: echo $it" }
        action("big", "runs:\n  using: composite\n  steps:\n$composite")
        action("small", "runs:\n  using: composite\n  steps:\n    - run: echo")
        val workflow = assertIs<WorkflowJson.Result.Decoded>(WorkflowJson.decode(steps("./big", "./small"))).workflow

        val graph =
            WorkflowDrawing.draw(
                workflow,
                maxNodes = 5,
                resolver = ReferenceResolver(RepositoryFiles(repo), parser),
                path = ".github/workflows/main.yml",
            )

        assertEquals(listOf("j0s0"), graph.styled("limit"))
        assertEquals("j0s0[\"<b>./big</b><br/>${WorkflowDrawing.NOT_EXPANDED}\"]", graph.box("j0s0"))
        assertTrue("subgraph j0s1 [\"<b>./small</b> #183; <i>#60;composite#62;</i>\"]" in graph.mermaid, graph.mermaid)
        assertEquals("Some references are not expanded, to stay within the limits.", graph.note)
    }

    @Test
    fun `after ten expansions that did not fit, no further one is read or tried`() {
        val big = (0 until 40).map { "actions/checkout@v$it" }.toTypedArray()
        for (index in 0 until 12) workflow("b$index.yml", steps(*big))
        val jobs =
            (0 until 12).joinToString(",") {
                """{"id":"call$it","type":"reusableWorkflowJob","needs":[],"uses":"./.github/workflows/b$it.yml"}"""
            }
        val json = """{"schema":1,"errors":[],"events":[],"jobs":[$jobs]}"""
        val workflow = assertIs<WorkflowJson.Result.Decoded>(WorkflowJson.decode(json)).workflow

        val graph =
            WorkflowDrawing.draw(
                workflow,
                maxNodes = 30,
                resolver = ReferenceResolver(RepositoryFiles(repo), parser),
                path = ".github/workflows/main.yml",
            )

        assertEquals((0 until WorkflowDrawing.MAX_FAILED_ATTEMPTS).map { "b$it.yml" }, parsed)
        assertEquals((0 until 12).map { "j${it}w" }, graph.styled("limit"))
        assertTrue(WorkflowDrawing.NOT_EXPANDED in graph.box("j11w"))
        assertEquals("Some references are not expanded, to stay within the limits.", graph.note)
    }

    @Test
    fun `deeply nested graphs stay within Mermaid's limits`() {
        val long = "<".repeat(200)
        for (level in 0..10) {
            val steps = (0 until 15).joinToString(",") { """{"name":"$long","run":"x","if":"$long"}""" }
            workflow(
                "w$level.yml",
                """{"schema":1,"errors":[],"events":[{"name":"push"}],"jobs":[""" +
                    """{"id":"call","type":"reusableWorkflowJob","needs":[],""" +
                    """"uses":"./.github/workflows/w${level + 1}.yml","with":{"$long":"x"}},""" +
                    """{"id":"steps","name":"$long","type":"job","needs":["call"],"if":"$long","steps":[$steps]}]}""",
            )
        }

        val graph = draw("w0.yml")

        assertTrue(graph.mermaid.length < 50_000, "${graph.mermaid.length} characters")
        assertTrue(graph.mermaid.lines().count { "-->" in it } <= WorkflowDrawing.MAX_EDGES)
        assertTrue("[\"calls <b>w2.yml</b>" in graph.mermaid, "some levels are expanded")
        assertTrue(WorkflowDrawing.NOT_EXPANDED in graph.mermaid, "the deepest are not")
        assertTrue("Some references are not expanded" in graph.note.orEmpty(), graph.note)
    }

    @Test
    fun `nested expansions filling the text budget stay within Mermaid's limits`() {
        val long = "<".repeat(200)
        for (index in 0 until 48) {
            val steps = (0 until 4).joinToString("\n") { "    - name: '$long'\n      if: '$long'\n      run: x" }
            action("c$index", "name: '$long'\nruns:\n  using: composite\n  steps:\n$steps")
        }
        workflow("main.yml", steps(*(0 until 48).map { "./c$it" }.toTypedArray()))

        val graph = draw("main.yml")

        assertTrue(graph.mermaid.length in 40_000..<50_000, "${graph.mermaid.length} characters")
        assertTrue(WorkflowDrawing.NOT_EXPANDED in graph.mermaid)
    }

    @Test
    fun `the inputs of a step that is an expanded composite are a box at the top of its subgraph`() {
        action("c", "runs:\n  using: composite\n  steps:\n    - run: echo\n    - run: again")
        workflow(
            "main.yml",
            """{"schema":1,"errors":[],"events":[],"jobs":[{"id":"main","type":"job","needs":[],""" +
                """"runsOn":"x","steps":[{"uses":"./c","with":{"a":"1"}}]}]}""",
        )

        val graph = draw("main.yml")

        assertTrue(
            """
            |        subgraph j0s0 ["<b>./c</b> #183; <i>#60;composite#62;</i>"]
            |            direction TB
            |            j0s0i("a: 1")
            |            j0s0s0["run: echo"]
            |            j0s0s1["run: again"]
            |            j0s0i ~~~ j0s0s0
            |            j0s0s0 --> j0s0s1
            |        end
            |
            """.trimMargin() in graph.mermaid,
            graph.mermaid,
        )
        assertEquals(listOf("j0s0i"), graph.styled("inputs"))
        assertFalse("sluide-inputs" in graph.mermaid, graph.mermaid)
    }

    @Test
    fun `the inputs box of a called workflow stands above each of its jobs that need none`() {
        workflow(
            "b.yml",
            """{"schema":1,"errors":[],"events":[],"jobs":[""" +
                """{"id":"one","type":"job","needs":[],"runsOn":"x","steps":[{"run":"a"}]},""" +
                """{"id":"two","type":"job","needs":[],"runsOn":"x","steps":[{"run":"b"}]},""" +
                """{"id":"three","type":"job","needs":["one"],"runsOn":"x","steps":[{"run":"c"}]}]}""",
        )
        workflow(
            "main.yml",
            """{"schema":1,"errors":[],"events":[],"jobs":[{"id":"a","type":"reusableWorkflowJob","needs":[],""" +
                """"uses":"./.github/workflows/b.yml","with":{"x":"1"}}]}""",
        )

        val graph = draw("main.yml")

        assertTrue(
            "        subgraph j0w [\"calls <b>b.yml</b>\"]\n            direction TB\n            j0wi(\"x: 1\")\n" in
                graph.mermaid,
            graph.mermaid,
        )
        assertTrue(
            "            j0wi ~~~ j0wj0\n            j0wi ~~~ j0wj1\n            j0wj0 --> j0wj2\n" in graph.mermaid,
            graph.mermaid,
        )
        assertFalse("j0wi ~~~ j0wj2" in graph.mermaid, graph.mermaid)
    }

    /** [json] drawn with the given limits, expanding what the repository holds. */
    private fun drawLimited(
        json: String,
        nodes: Int = 100,
        edges: Int = 100,
        text: Int = 100_000,
    ): WorkflowGraph {
        val workflow = assertIs<WorkflowJson.Result.Decoded>(WorkflowJson.decode(json)).workflow
        return WorkflowDrawing.draw(
            workflow,
            nodes,
            edges,
            text,
            ReferenceResolver(RepositoryFiles(repo), parser),
            ".github/workflows/main.yml",
        )
    }

    /** The smallest text limit at which [json] still draws with [needed] and without a note. */
    private fun tightestText(
        json: String,
        needed: String,
    ): Int {
        // Binary search: a larger limit never draws less.
        var low = 1
        var high = 3_000
        while (low < high) {
            val middle = (low + high) / 2
            val graph = drawLimited(json, text = middle)
            if (graph.note == null && needed in graph.mermaid) high = middle else low = middle + 1
        }
        return low
    }

    @Test
    fun `inputs of steps in a composite never keep it from being expanded`() {
        action(
            "c",
            "runs:\n  using: composite\n  steps:\n    - uses: x/y@v1\n      with: {a: 1}\n" +
                "    - uses: x/z@v1\n      with: {b: 2}",
        )
        workflow(
            "main.yml",
            """{"schema":1,"errors":[],"events":[],"jobs":[{"id":"main","type":"job","needs":[],""" +
                """"runsOn":"x","steps":[{"uses":"./c"}]}]}""",
        )
        val json = workflows.getValue("main.yml")

        val full = drawLimited(json)
        assertEquals(2, Regex("sluide-inputs").findAll(full.mermaid).count(), full.mermaid)

        // Two boxes inside the composite, one link between them: all it needs; the inputs in them take neither.
        for (graph in listOf(drawLimited(json, nodes = 2), drawLimited(json, edges = 1))) {
            assertNull(graph.note, graph.mermaid)
            assertTrue("subgraph j0s0 [" in graph.mermaid, graph.mermaid)
            assertTrue("j0s0s0[" in graph.mermaid && "j0s0s1[" in graph.mermaid, graph.mermaid)
            assertEquals(2, Regex("sluide-inputs").findAll(graph.mermaid).count(), graph.mermaid)
        }
        val tight = drawLimited(json, text = tightestText(json, "subgraph j0s0 ["))
        assertNull(tight.note, tight.mermaid)
        assertTrue("subgraph j0s0 [" in tight.mermaid, tight.mermaid)
        assertFalse("sluide-inputs" in tight.mermaid, tight.mermaid)
    }

    @Test
    fun `inputs asked for by an expansion that did not fit are forgotten, those of the next one are drawn`() {
        val steps = (0 until 4).joinToString("") { "    - uses: x/y$it@v1\n      with: {k: v}\n" }
        action("a", "runs:\n  using: composite\n  steps:\n$steps")
        action("b", "runs:\n  using: composite\n  steps:\n    - uses: x/z@v1\n      with: {k: v}\n")
        workflow(
            "main.yml",
            """{"schema":1,"errors":[],"events":[],"jobs":[{"id":"main","type":"job","needs":[],""" +
                """"runsOn":"x","steps":[{"uses":"./a"},{"uses":"./b"}]}]}""",
        )
        val json = workflows.getValue("main.yml")

        // a's three links do not fit in one; b has none, and the job's link is the one.
        val graph = drawLimited(json, edges = 1)

        assertTrue(WorkflowDrawing.NOT_EXPANDED in graph.box("j0s0"), graph.mermaid)
        assertTrue("subgraph j0s1 [" in graph.mermaid, graph.mermaid)
        assertTrue(graph.box("j0s1s0").endsWith("<div class='sluide-inputs'>k: v</div>\"]"), graph.mermaid)
        assertEquals(1, Regex("sluide-inputs").findAll(graph.mermaid).count(), graph.mermaid)
    }

    @Test
    fun `the inputs box of an expansion that did not fit is forgotten`() {
        action("inner", "runs:\n  using: composite\n  steps:\n    - run: echo\n    - run: again")
        action("outer", "runs:\n  using: composite\n  steps:\n    - uses: ./inner\n      with: {k: v}\n    - run: x")
        workflow(
            "main.yml",
            """{"schema":1,"errors":[],"events":[],"jobs":[{"id":"main","type":"job","needs":[],""" +
                """"runsOn":"x","steps":[{"uses":"./outer"}]}]}""",
        )
        val json = workflows.getValue("main.yml")

        assertTrue("j0s0s0i(" in drawLimited(json).mermaid)
        // outer's link and inner's do not fit in one: outer is a box, and inner's inputs box went with it.
        val graph = drawLimited(json, edges = 1)
        assertTrue(WorkflowDrawing.NOT_EXPANDED in graph.box("j0s0"), graph.mermaid)
        assertFalse("j0s0s0i" in graph.mermaid || "k: v" in graph.mermaid, graph.mermaid)
    }

    @Test
    fun `the inputs of a reusable workflow job never keep its called workflow from being expanded`() {
        workflow("b.yml", steps("actions/checkout@v6"))
        workflow(
            "main.yml",
            """{"schema":1,"errors":[],"events":[],"jobs":[{"id":"a","type":"reusableWorkflowJob","needs":[],""" +
                """"uses":"./.github/workflows/b.yml","with":{"x":"1"}}]}""",
        )
        val json = workflows.getValue("main.yml")

        assertTrue("j0wi(" in drawLimited(json).mermaid)
        val tight = drawLimited(json, text = tightestText(json, "j0wj0s0["))
        assertNull(tight.note, tight.mermaid)
        assertTrue("j0wj0s0[" in tight.mermaid, tight.mermaid)
        assertFalse("j0wi" in tight.mermaid, tight.mermaid)
    }

    @Test
    fun `hostile image names, action names and inputs of actions stay text`() {
        val hostile = "\"] --> evil[\"x\"] %% <script>alert(1)</script> `md` #35; ;"
        action("d", "runs:\n  using: docker\n  image: '$hostile'")
        action(
            "c",
            "name: '$hostile'\nruns:\n  using: composite\n  steps:\n    - uses: ./d\n" +
                "      with: {'$hostile': '$hostile'}",
        )
        workflow(
            "main.yml",
            """{"schema":1,"errors":[],"events":[],"jobs":[{"id":"main","type":"job","needs":[],""" +
                """"runsOn":"x","steps":[{"uses":"./c"},{"uses":"./d"}]}]}""",
        )

        val graph = draw("main.yml")

        val quoted = Regex("\"([^\"]*)\"").findAll(graph.mermaid).map { it.groupValues[1] }.toList()
        assertTrue(quoted.size >= 4, "$quoted")
        assertTrue(
            "</i><div class='sluide-inputs'>#34;" in graph.box("j0s0s0") && "#60;Docker (" in graph.mermaid,
            graph.mermaid,
        )
        for (text in quoted) assertTrue(text.matches(SAFE_LABEL), "unsafe label: $text")
        for (line in graph.mermaid.lines()) {
            val outside = line.replace(Regex("\"[^\"]*\""), "")
            assertFalse("%%" in outside || "<" in outside || "evil" in outside, "outside labels: $line")
        }
    }

    @Test
    fun `paths are normalised against the repository's root`() {
        assertEquals("a/b", ReferenceResolver.normalise("./a/./b/"))
        assertEquals("b", ReferenceResolver.normalise("./a/../b"))
        assertEquals("a/b", ReferenceResolver.normalise("./a//b"))
        assertEquals("", ReferenceResolver.normalise("./"))
        assertNull(ReferenceResolver.normalise("./../x"))
        assertNull(ReferenceResolver.normalise("./a/../../x"))
        assertNull(ReferenceResolver.normalise(".//etc"))
    }

    companion object {
        /** Schema-1 JSON of a workflow with one job `call` calling [uses]. */
        fun calls(
            uses: String,
            problems: Int = 0,
        ): String {
            val errors = (1..problems).joinToString(",") { """{"message":"Problem $it","line":1}""" }
            return """{"schema":1,"errors":[$errors],"events":[],""" +
                """"jobs":[{"id":"call","type":"reusableWorkflowJob","needs":[],"uses":"$uses"}]}"""
        }

        /** Schema-1 JSON of a workflow with one job `main` whose steps use [uses]. */
        fun steps(vararg uses: String): String =
            """{"schema":1,"errors":[],"events":[],"jobs":[{"id":"main","type":"job","needs":[],""" +
                """"runsOn":"ubuntu-latest","steps":[${uses.joinToString(",") { """{"uses":"$it"}""" }}]}]}"""
    }
}
