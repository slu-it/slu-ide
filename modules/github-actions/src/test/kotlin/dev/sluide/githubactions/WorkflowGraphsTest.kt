package dev.sluide.githubactions

import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowGraphsTest {
    @Test
    fun `draws this repository's CI workflow`() {
        var asked: Pair<String, String>? = null
        val graph =
            WorkflowGraphs.of("ci.yml", "name: CI") { fileName, text ->
                asked = fileName to text
                sampleJson("ci")
            }

        assertEquals("ci.yml" to "name: CI", asked)
        assertNull(graph.note)
        assertFalse(graph.isEmpty)
        assertEquals(
            """
            flowchart LR
                subgraph triggers ["Triggers"]
                    direction TB
                    t0(["push #183; branches: main, master"])
                    t1(["pull_request"])
                end
                subgraph j0 ["Build (#36;#123;#123; matrix.os #125;#125;) #183; #36;#123;#123; matrix.os #125;#125; #183; matrix os (2)"]
                    direction TB
                    j0s0["actions/checkout@v6"]
                    j0s1["actions/setup-java@v6"]
                    j0s2["gradle/actions/setup-gradle@v6"]
                    j0s3["Check docs index<br/>if: runner.os #61;#61; #39;Linux#39;"]
                    j0s4["Install virtual display<br/>if: runner.os #61;#61; #39;Linux#39;"]
                    j0s5["Build<br/>if: runner.os #61;#61; #39;Linux#39;"]
                    j0s6["Build without UI tests<br/>if: runner.os #61;#61; #39;macOS#39;"]
                    j0s7["Upload test reports<br/>actions/upload-artifact@v7<br/>if: failure()"]
                    j0s0 --> j0s1
                    j0s1 --> j0s2
                    j0s2 --> j0s3
                    j0s3 --> j0s4
                    j0s4 --> j0s5
                    j0s5 --> j0s6
                    j0s6 --> j0s7
                end
                triggers --> j0
                classDef blackbox stroke-width:3px
                classDef unresolved stroke-dasharray: 5 5
                classDef failed stroke:#d9534f,stroke-width:2px
                classDef cycle stroke:#f0ad4e,stroke-width:2px,stroke-dasharray: 2 2
                classDef limit stroke-dasharray: 1 4
                class j0s0,j0s1,j0s2,j0s7 blackbox

            """.trimIndent(),
            graph.mermaid,
        )
    }

    @Test
    fun `a failing parser becomes a note`() {
        val graph = WorkflowGraphs.of("ci.yml", "") { _, _ -> throw TimeoutException("Parsing took over 5 s") }

        assertTrue(graph.isEmpty)
        assertEquals("", graph.mermaid)
        assertEquals("The workflow parser failed: Parsing took over 5 s", graph.note)
        assertEquals(
            "The workflow parser failed: IllegalStateException",
            WorkflowGraphs.of("ci.yml", "") { _, _ -> throw IllegalStateException() }.note,
        )
    }

    @Test
    fun `an interrupted parser keeps the thread's interrupt flag`() {
        val graph = WorkflowGraphs.of("ci.yml", "") { _, _ -> throw InterruptedException("stop") }

        assertTrue(Thread.interrupted(), "interrupt flag restored (and cleared here)")
        assertEquals("The workflow parser failed: stop", graph.note)
    }

    @Test
    fun `output the module cannot read becomes a note`() {
        for ((json, note) in listOf(
            "not json" to "Unsupported parser output (no JSON object).",
            """{"schema":2,"jobs":[]}""" to "Unsupported parser output (schema 2, expected 1).",
        )) {
            val graph = WorkflowGraphs.of("ci.yml", "") { _, _ -> json }
            assertTrue(graph.isEmpty)
            assertEquals(note, graph.note)
        }
    }
}
