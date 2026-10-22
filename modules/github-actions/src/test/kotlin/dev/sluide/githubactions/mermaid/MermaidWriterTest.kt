package dev.sluide.githubactions.mermaid

import dev.sluide.githubactions.model.UsesReference
import kotlin.test.Test
import kotlin.test.assertEquals

class MermaidWriterTest {
    @Test
    fun `a reference with an expansion is drawn as its subgraph under the reference's id, with styles inside`() {
        val reference = UsesReference.LocalAction("./a")
        val expansion =
            Cluster(
                "x",
                "./a",
                listOf(Node("j0s0e0", listOf("run: x")), Node("j0s0e1", listOf("gone"), style = Style.UNRESOLVED)),
                listOf(Link("j0s0e0", "j0s0e1")),
            )
        val diagram =
            Diagram(
                listOf(
                    Cluster(
                        "j0",
                        "a",
                        listOf(
                            ReferenceNode("j0s0", listOf("./a"), reference, expansion),
                            ReferenceNode("j0s1", listOf("./b"), reference),
                        ),
                        listOf(Link("j0s0", "j0s1", label = "next", dotted = true)),
                    ),
                ),
                emptyList(),
            )

        val lines = MermaidWriter.write(diagram).lines()

        assertEquals(
            listOf(
                "flowchart LR",
                "    subgraph j0 [\"a\"]",
                "        direction TB",
                "        subgraph j0s0 [\"./a\"]",
                "            direction TB",
                "            j0s0e0[\"run: x\"]",
                "            j0s0e1[\"gone\"]",
                "            j0s0e0 --> j0s0e1",
                "        end",
                "        j0s1[\"./b\"]",
                "        j0s0 -.->|\"next\"| j0s1",
                "    end",
            ),
            lines.take(12),
        )
        assertEquals(listOf("    class j0s1 blackbox", "    class j0s0e1 unresolved", ""), lines.takeLast(3))
    }
}
