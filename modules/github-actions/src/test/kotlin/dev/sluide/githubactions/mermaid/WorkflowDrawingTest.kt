package dev.sluide.githubactions.mermaid

import dev.sluide.githubactions.WorkflowGraph
import dev.sluide.githubactions.model.Event
import dev.sluide.githubactions.model.Job
import dev.sluide.githubactions.model.JobBody
import dev.sluide.githubactions.model.Matrix
import dev.sluide.githubactions.model.Problem
import dev.sluide.githubactions.model.Step
import dev.sluide.githubactions.model.StepAction
import dev.sluide.githubactions.model.UsesReference
import dev.sluide.githubactions.model.UsesSite
import dev.sluide.githubactions.model.Workflow
import dev.sluide.githubactions.model.WorkflowJson
import dev.sluide.githubactions.sampleJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowDrawingTest {
    private fun drawSample(name: String): WorkflowGraph =
        WorkflowDrawing.draw(assertIs<WorkflowJson.Result.Decoded>(WorkflowJson.decode(sampleJson(name))).workflow)

    private fun run(
        script: String,
        name: String? = null,
        condition: String? = null,
    ) = Step(null, name, null, StepAction.Run(script), condition)

    private fun uses(
        text: String,
        name: String? = null,
    ) = Step(null, name, null, StepAction.Uses(text, UsesReference.parse(text, UsesSite.STEP)), null)

    private fun job(
        id: String,
        vararg steps: Step,
        needs: List<String> = emptyList(),
        condition: String? = null,
        name: String? = null,
    ) = Job(id, name, null, needs, condition, "ubuntu-latest", null, JobBody.Steps(steps.toList()))

    private fun workflow(
        vararg jobs: Job,
        events: List<Event> = listOf(Event("push", null)),
        problems: List<Problem> = emptyList(),
    ) = Workflow(null, problems, events, jobs.toList())

    @Test
    fun `needs link the job subgraphs, conditions make the links dotted and labelled`() {
        val graph = drawSample("needs-chain")

        assertNull(graph.note)
        assertFalse(graph.isEmpty)
        assertEquals(
            """
            flowchart LR
                subgraph triggers ["Triggers"]
                    direction LR
                    t0(["push"])
                end
                subgraph j0 ["lint #183; ubuntu-latest"]
                    direction TB
                    j0s0["run: ./gradlew spotlessCheck"]
                end
                subgraph j1 ["Unit tests #183; #91;self-hosted, linux#93;"]
                    direction TB
                    j1s0["run: ./gradlew test"]
                end
                subgraph j2 ["ui-test #183; #123;group: big-runners, labels: #91;linux, x64#93;#125;"]
                    direction TB
                    j2s0["run: ./gradlew uiTest"]
                end
                subgraph j3 ["package #183; #36;#123;#123; inputs.runner #124;#124; #39;ubuntu-latest#39; #125;#125;"]
                    direction TB
                    j3s0["Package"]
                end
                subgraph j4 ["always-report #183; ubuntu-latest"]
                    direction TB
                    j4s0["run: echo done"]
                end
                triggers --> j0
                j0 --> j1
                j0 -.->|"if: github.event_name #61;#61; #39;push#39;"| j2
                j1 -.->|"if: #36;#123;#123; #33;cancelled() #125;#125;"| j3
                j2 -.->|"if: #36;#123;#123; #33;cancelled() #125;#125;"| j3
                j3 -.->|"if: always()"| j4

            """.trimIndent() + CLASS_DEFS,
            graph.mermaid,
        )
    }

    @Test
    fun `reusable workflow jobs are subgraphs around a black box, events show their filters`() {
        val graph = drawSample("reusable-and-events")

        assertNull(graph.note)
        assertEquals(
            """
            flowchart LR
                subgraph triggers ["Triggers"]
                    direction LR
                    t0(["push #183; branches: main, release/#42;#42;#59; tags: v#42;#59; pa#8230;"])
                    t1(["pull_request #183; branches: main#59; types: opened, s#8230;"])
                    t2(["schedule #183; cron: 0 3 #42; #42; 1, 30 2 #42; #42; #42;"])
                    t3(["workflow_dispatch #183; inputs: runner, dry-run"])
                    t4(["workflow_call"])
                    t5(["release #183; types: published"])
                end
                subgraph j0 ["local #183; calls <b>./.github/workflows/build.yml</b>"]
                    direction TB
                    j0w["<b>./.github/workflows/build.yml</b><div class='sluide-inputs'>java: 25<br/>flags: a b<br/>ref: #36;#123;#123; github.sha #125;#125;</div>"]
                end
                subgraph j1 ["Deploy #36;#123;#123; github.ref_name #125;#125; #183; calls <b>owner/repo/.github/workflows/deploy.yml@v1</b>"]
                    direction TB
                    j1w["<b>owner/repo/.github/workflows/deploy.yml@v1</b><br/>secrets: inherit<div class='sluide-inputs'>environment: production</div>"]
                end
                subgraph j2 ["remote-explicit #183; calls <b>owner/repo/.github/workflows/deploy.yml@0123456#8230;</b>"]
                    direction TB
                    j2w["<b>owner/repo/.github/workflows/deploy.yml@0123456#8230;</b>"]
                end
                subgraph j3 ["steps #183; ubuntu-latest"]
                    direction TB
                    j3s0["<b>./local-action</b>"]
                    j3s1["Check out<br/><b>actions/checkout@v4</b>"]
                    j3s2["<b>owner/repo/path/to/action@0123456789abcdef01234#8230;</b>"]
                    j3s3["<b>docker://alpine:3</b><br/>if: success() #38;#38; github.ref #61;#61; #39;refs/heads/main#39;"]
                    j3s4["run: echo #34;#36;#123;#123; github.ref #125;#125;#34;"]
                    j3s5["Quoted #36;#123;#123; github.sha #125;#125;<br/>if: #36;#123;#123; github.event_name #61;#61; #39;push#39; #125;#125;"]
                    j3s0 --> j3s1
                    j3s1 --> j3s2
                    j3s2 --> j3s3
                    j3s3 --> j3s4
                    j3s4 --> j3s5
                end
                triggers --> j0
                j0 --> j1
                j0 --> j2
                j1 --> j3
                j2 --> j3

            """.trimIndent() + CLASS_DEFS +
                "    class j0w,j1w,j2w,j3s0,j3s1,j3s2,j3s3 blackbox\n",
            graph.mermaid,
        )
    }

    @Test
    fun `matrices show their axes and count in the job's title`() {
        val titles = Regex("""subgraph j\d \["(.*)"]""").findAll(drawSample("matrix").mermaid).map { it.groupValues[1] }

        assertEquals(
            listOf(
                "static #183; #36;#123;#123; matrix.os #125;#125; #183; matrix os#215;java#215;experimental (4)",
                "setup #183; ubuntu-latest",
                "dynamic #183; ubuntu-latest #183; matrix (dynamic)",
                "partly-dynamic #183; ubuntu-latest #183; matrix os (dynamic)",
            ),
            titles.toList(),
        )
    }

    @Test
    fun `problems and unknown needs go into the note, the known links are drawn`() {
        val graph = drawSample("broken-schema")

        assertEquals(
            "7 problems; first: Unexpected value 'colour' (line 2). Not linked, unknown jobs: deploy needs missing.",
            graph.note,
        )
        assertTrue("    j0 -.->|\"if: #36;#123;#123; github.ref #61;#61; #125;#125;\"| j1\n" in graph.mermaid)
        assertFalse("missing" in graph.mermaid)
    }

    @Test
    fun `steps without a name show their script's first line, empty scripts just run`() {
        val graph = drawSample("odd-steps-and-matrix")

        assertEquals("5 problems; first: Unexpected value 'maybe' (line 4).", graph.note)
        assertTrue(
            "        j0s0[\"run:\"]\n        j0s1[\"run:\"]\n        j0s2[\"<b>actions/checkout@v4</b>\"]\n" in
                graph.mermaid,
        )
        assertTrue("subgraph j1 [\"b #183; ubuntu-latest #183; matrix (0)\"]" in graph.mermaid)
    }

    @Test
    fun `without jobs the graph is empty with the problems or a hint as note`() {
        val syntax = drawSample("broken-syntax")
        assertTrue(syntax.isEmpty)
        assertEquals("", syntax.mermaid)
        assertEquals("1 problem: Missing closing \"quote at line 8, column 1 (line 8).", syntax.note)

        val none = WorkflowDrawing.draw(workflow())
        assertTrue(none.isEmpty)
        assertEquals("No jobs in this workflow.", none.note)
    }

    @Test
    fun `a job with an if but no needs labels its trigger link, without events nothing links it`() {
        val graph = WorkflowDrawing.draw(workflow(job("a", run("x"), condition = "github.actor == 'me'")))
        assertTrue("    triggers -.->|\"if: github.actor #61;#61; #39;me#39;\"| j0\n" in graph.mermaid)

        val quiet = WorkflowDrawing.draw(workflow(job("a", run("x")), events = emptyList()))
        assertFalse("triggers" in quiet.mermaid)
        assertFalse("-->" in quiet.mermaid)
    }

    @Test
    fun `an if no link carries goes into the job's title`() {
        val graph =
            WorkflowDrawing.draw(
                workflow(
                    job("a", run("x"), condition = "always()"),
                    job("b", run("y"), needs = listOf("missing"), condition = "failure()"),
                    events = emptyList(),
                ),
            )

        assertTrue("subgraph j0 [\"a #183; ubuntu-latest #183; if: always()\"]" in graph.mermaid)
        assertTrue("subgraph j1 [\"b #183; ubuntu-latest #183; if: failure()\"]" in graph.mermaid)

        val cut = WorkflowDrawing.draw(workflow(job("a", run("x"), condition = "always()")), maxEdges = 0)
        assertTrue("subgraph j0 [\"a #183; ubuntu-latest #183; if: always()\"]" in cut.mermaid)
        val linked = WorkflowDrawing.draw(workflow(job("a", run("x"), condition = "always()")))
        assertTrue("subgraph j0 [\"a #183; ubuntu-latest\"]" in linked.mermaid)
    }

    @Test
    fun `a reusable workflow job's title shows its matrix`() {
        val matrix = Matrix(null, mapOf("env" to listOf("dev", "prod")), emptyList(), emptyList(), null, null)
        val call =
            JobBody.WorkflowCall(
                "./.github/workflows/d.yml",
                UsesReference.parse("./.github/workflows/d.yml", UsesSite.JOB),
                emptyMap(),
                false,
            )
        val graph = WorkflowDrawing.draw(workflow(Job("deploy", null, null, emptyList(), null, null, matrix, call)))

        assertTrue(
            "subgraph j0 [\"deploy #183; calls <b>./.github/workflows/d.yml</b> #183; matrix env (2)\"]" in
                graph.mermaid,
        )
    }

    @Test
    fun `blank names, ids and texts never make an empty label`() {
        val blankJob = Job("", "", null, emptyList(), null, " ", null, JobBody.Steps(listOf(uses(" ", name = ""))))
        val graph =
            WorkflowDrawing.draw(
                workflow(
                    blankJob,
                    Job(
                        "b",
                        "",
                        null,
                        emptyList(),
                        "",
                        "ubuntu-latest",
                        null,
                        JobBody.Steps(listOf(run("x", name = " "))),
                    ),
                    events = listOf(Event("", " ")),
                ),
            )

        assertFalse(Regex("\"\\s*\"").containsMatchIn(graph.mermaid), graph.mermaid)
        assertTrue("        t0([\"#32;\"])\n" in graph.mermaid)
        assertTrue("    subgraph j0 [\"#32;\"]\n" in graph.mermaid)
        assertTrue("        j0s0[\"#32;\"]\n" in graph.mermaid)
        assertTrue("    subgraph j1 [\"b #183; ubuntu-latest\"]\n" in graph.mermaid, "no stray separator")
        assertTrue("        j1s0[\"run: x\"]\n" in graph.mermaid)
    }

    @Test
    fun `triggers lay out left to right so they stack, a reference is bold below its step name`() {
        val graph =
            WorkflowDrawing.draw(
                workflow(
                    job("a", uses("actions/checkout@v4", name = "Check out")),
                    events = listOf(Event("push", null), Event("pull_request", null)),
                ),
            )

        assertTrue("    subgraph triggers [\"Triggers\"]\n        direction LR\n" in graph.mermaid, graph.mermaid)
        assertTrue("        j0s0[\"Check out<br/><b>actions/checkout@v4</b>\"]\n" in graph.mermaid, graph.mermaid)
    }

    private fun withInputs(
        text: String,
        inputs: Map<String, String>,
    ) = uses(text).copy(inputs = inputs)

    @Test
    fun `a step's inputs are a framed block in its box, capped, a value cut to its first line`() {
        val inputs =
            linkedMapOf("script" to "\n  echo one\n\n  echo two\n", "ref" to "\${{ github.ref }}") +
                (1..8).associate { "k$it" to "v$it" }
        val graph = WorkflowDrawing.draw(workflow(job("a", withInputs("actions/checkout@v4", inputs), run("x"))))

        assertTrue(
            """
            |        j0s0["<b>actions/checkout@v4</b><div class='sluide-inputs'>script: echo one #8230;<br/>ref: #36;#123;#123; github.ref #125;#125;<br/>k1: v1<br/>k2: v2<br/>k3: v3<br/>k4: v4<br/>k5: v5<br/>k6: v6<br/>#43;2 more</div>"]
            |        j0s1["run: x"]
            |        j0s0 --> j0s1
            |    end
            |
            """.trimMargin() in graph.mermaid,
            graph.mermaid,
        )
        assertFalse("j0s0i" in graph.mermaid || "class j0s0 inputs" in graph.mermaid, graph.mermaid)
    }

    @Test
    fun `an empty with gives no inputs element`() {
        val graph = WorkflowDrawing.draw(workflow(job("a", withInputs("actions/checkout@v4", emptyMap()))))

        assertFalse("j0s0i" in graph.mermaid, graph.mermaid)
        assertFalse(" inputs" in graph.mermaid.substringAfter("class "), graph.mermaid)
        assertFalse("sluide-inputs" in graph.mermaid, graph.mermaid)
    }

    /** The smallest text limit at which [draw] without inputs gives a graph with no note. */
    private fun tightestText(draw: (Int) -> WorkflowGraph): Int {
        var limit = 3_000
        while (limit > 0 && draw(limit - 1).note == null) limit--
        return limit
    }

    @Test
    fun `inputs never take room from steps, links or the text a job needs`() {
        fun steps(inputs: Boolean) =
            arrayOf(
                withInputs("a/b@v1", if (inputs) mapOf("x" to "1") else emptyMap()),
                withInputs("c/d@v1", if (inputs) mapOf("y" to "2") else emptyMap()),
            )

        fun draw(
            inputs: Boolean,
            nodes: Int = 100,
            edges: Int = 100,
            text: Int = 100_000,
        ) = WorkflowDrawing.draw(workflow(job("a", *steps(inputs))), nodes, edges, text)

        // Inputs in a box take no box and no link, only text.
        val limits =
            listOf(
                Triple(3, 100, 100_000) to true,
                Triple(100, 2, 100_000) to true,
                Triple(100, 100, tightestText { draw(false, text = it) }) to false,
            )
        for ((limit, shown) in limits) {
            val (nodes, edges, text) = limit
            assertNull(draw(false, nodes, edges, text).note, "the steps alone fit $limits")
            val graph = draw(true, nodes, edges, text)

            assertTrue("j0s0[" in graph.mermaid && "j0s1[" in graph.mermaid, graph.mermaid)
            assertTrue("j0s0 --> j0s1" in graph.mermaid, graph.mermaid)
            assertEquals(shown, "sluide-inputs" in graph.mermaid, graph.mermaid)
            assertNull(graph.note)
        }
    }

    @Test
    fun `inputs that fit are shown in the order of the steps, the boxes after them without`() {
        fun steps(inputs: Boolean) =
            arrayOf(
                withInputs("a/b@v1", if (inputs) mapOf("x" to "1") else emptyMap()),
                withInputs("c/d@v1", if (inputs) mapOf("y" to "2") else emptyMap()),
            )
        val tightest = tightestText { WorkflowDrawing.draw(workflow(job("a", *steps(false))), maxText = it) }
        val first = MermaidLabels.inputs(listOf("x: 1")).length
        val graph = WorkflowDrawing.draw(workflow(job("a", *steps(true))), maxText = tightest + first)

        assertTrue("j0s0[\"<b>a/b@v1</b><div class='sluide-inputs'>x: 1</div>\"]" in graph.mermaid, graph.mermaid)
        assertTrue("j0s1[\"<b>c/d@v1</b>\"]" in graph.mermaid, graph.mermaid)
        assertNull(graph.note)
    }

    @Test
    fun `long conditions are cut on the link`() {
        val condition = "github.event_name == 'push' && github.ref == 'refs/heads/main'"
        val graph =
            WorkflowDrawing.draw(
                workflow(job("a", run("x")), job("b", run("y"), needs = listOf("a"), condition = condition)),
            )

        assertTrue(
            "    j0 -.->|\"if: github.event_name #61;#61; #39;push#39; #38;#38; github.ref #61;#8230;\"| j1\n" in
                graph.mermaid,
        )
    }

    @Test
    fun `a job without steps gets a placeholder, a job needing itself no self link`() {
        val graph = WorkflowDrawing.draw(workflow(job("a"), job("b", run("x"), needs = listOf("b", "a"))))

        assertTrue("        j0none[\"no steps\"]\n" in graph.mermaid)
        assertTrue("    j0 --> j1\n" in graph.mermaid)
        assertFalse("j1 --> j1" in graph.mermaid)
    }

    @Test
    fun `hostile names, scripts and conditions stay text`() {
        val hostile = "\"] --> evil[\"x\"] %% <script>alert(1)</script> `md` #35; ; \n|end|"
        val graph =
            WorkflowDrawing.draw(
                workflow(
                    job(
                        "a",
                        run(hostile, name = hostile, condition = hostile),
                        uses(hostile, name = hostile),
                        withInputs("x/y@v1", mapOf(hostile to hostile, "k" to hostile)),
                        name = hostile,
                    ),
                    job("b", run("x"), needs = listOf("a"), condition = hostile),
                    Job(
                        "c",
                        hostile,
                        null,
                        emptyList(),
                        null,
                        null,
                        null,
                        JobBody.WorkflowCall(
                            hostile,
                            UsesReference.parse(hostile, UsesSite.JOB),
                            mapOf(hostile to hostile),
                            true,
                        ),
                    ),
                    events = listOf(Event(hostile, hostile)),
                ),
            )

        val quoted = Regex("\"([^\"]*)\"").findAll(graph.mermaid).map { it.groupValues[1] }.toList()
        assertTrue(quoted.size >= 10, "$quoted")
        assertTrue("j0s2[\"<b>x/y@v1</b><div class='sluide-inputs'>#34;" in graph.mermaid, graph.mermaid)
        assertTrue("secrets: inherit<div class='sluide-inputs'>#34;" in graph.mermaid, graph.mermaid)
        for (text in quoted) assertTrue(text.matches(SAFE_LABEL), "unsafe label: $text")
        for (line in graph.mermaid.lines()) {
            val outside = line.replace(Regex("\"[^\"]*\""), "")
            assertFalse("%%" in outside || "<" in outside || "evil" in outside, "outside labels: $line")
        }
    }

    @Test
    fun `over the node cap the rest is left out and the note says so`() {
        val steps = (1..10).map { run("step $it") }.toTypedArray()
        val graph = WorkflowDrawing.draw(workflow(job("a", *steps), job("b", run("x"))), maxNodes = 5)

        assertEquals("Too large: showing 5 boxes, 1 of 2 jobs.", graph.note)
        assertTrue("j0s3[" in graph.mermaid)
        assertFalse("j0s4" in graph.mermaid || "j1" in graph.mermaid)
    }

    @Test
    fun `over the edge cap the rest of the links is left out`() {
        val steps = (1..5).map { run("step $it") }.toTypedArray()
        val graph =
            WorkflowDrawing.draw(
                workflow(job("a", *steps), job("b", run("x"), needs = listOf("a"))),
                maxEdges = 2,
            )

        assertEquals("Too large: showing the first 2 links.", graph.note)
        assertEquals(2, graph.mermaid.lines().count { "-->" in it })
    }

    @Test
    fun `huge workflows stay within Mermaid's limits`() {
        // Every label at its longest: each character escaped, cut at 96 characters.
        val long = "<".repeat(200)
        val linked =
            (0 until 300).map { index ->
                job(
                    "job$index",
                    *(0 until 5).map { run(long, name = long, condition = long) }.toTypedArray(),
                    needs = if (index == 0) emptyList() else listOf("job${index - 1}", "job0"),
                    condition = long,
                    name = long,
                )
            }
        // Without events, every job's condition goes into its title; one step each makes the most subgraphs.
        val unlinked =
            (0 until 300).map { index ->
                Job(
                    "job$index",
                    long,
                    null,
                    emptyList(),
                    long,
                    long,
                    null,
                    JobBody.Steps(listOf(run(long, condition = long))),
                )
            }

        for ((jobs, events) in listOf(linked to listOf(Event("push", long)), unlinked to emptyList())) {
            val graph = WorkflowDrawing.draw(workflow(*jobs.toTypedArray(), events = events))

            assertTrue(graph.mermaid.length < 50_000, "${graph.mermaid.length} characters")
            assertTrue(graph.mermaid.lines().count { "-->" in it } <= WorkflowDrawing.MAX_EDGES)
            assertTrue(graph.note.orEmpty().startsWith("Too large: showing "), graph.note)
        }
    }

    companion object {
        /** Letters, digits, `_-./:@(),`, blanks, entity codes, line breaks, the bold and italic tags and the inputs block. */
        private val SAFE_LABEL =
            Regex("""([A-Za-z0-9 _\-./:@(),]|#\d+;|</?(br|b|i)/?>|<div class='sluide-inputs'>|</div>)*""")

        private val CLASS_DEFS =
            """
            |    classDef blackbox stroke-width:3px
            |    classDef unresolved stroke-dasharray: 5 5
            |    classDef failed stroke:#d9534f,stroke-width:2px
            |    classDef cycle stroke:#f0ad4e,stroke-width:2px,stroke-dasharray: 2 2
            |    classDef limit stroke-dasharray: 1 4
            |    classDef inputs stroke-dasharray: 4 3,stroke-width:1px
            |
            """.trimMargin()
    }
}
