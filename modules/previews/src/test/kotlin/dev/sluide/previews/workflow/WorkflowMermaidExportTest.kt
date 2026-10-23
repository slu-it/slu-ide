package dev.sluide.previews.workflow

import dev.sluide.githubactions.WorkflowGraph
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkflowMermaidExportTest {
    @TempDir
    lateinit var dir: Path

    private fun graph(
        note: String? = null,
        empty: Boolean = false,
    ) = WorkflowGraph("flowchart LR\n  a[build]", note, empty)

    private fun export(graph: WorkflowGraph?) = WorkflowMermaidExport { _, _ -> graph }

    @Test
    fun `the item is Mermaid and the target is named after the workflow`() {
        val export = export(null)
        assertEquals("Mermaid", export.label)
        assertEquals(Path("/r/.github/workflows/ci.mmd"), export.target(Path("/r/.github/workflows/ci.yml")))
        assertEquals(Path("/r/.github/workflows/ci.mmd"), export.target(Path("/r/.github/workflows/ci.yaml")))
    }

    @Test
    fun `writing puts the graph's standalone Mermaid source into the target`() {
        val graph =
            WorkflowGraph(
                "flowchart LR\n  i(\"x: 1\")\n  a[build]",
                null,
                false,
                sideLinks = listOf("i" to "a"),
                exportMermaid = "flowchart LR\n  i(\"x: 1\")\n  a[build]\n  i -.-> a",
            )
        val target = dir.resolve("ci.mmd").apply { writeText("old") }
        val problems = export(graph).write(dir.resolve("ci.yml"), "x", target)
        assertEquals(graph.exportMermaid, target.readText())
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `the note comes back as the only problem`() {
        val target = dir.resolve("ci.mmd")
        assertEquals(listOf("2 errors"), export(graph("2 errors")).write(dir.resolve("ci.yml"), "x", target))
    }

    @Test
    fun `a graph not drawn yet fails and leaves the target as it was`() {
        val target = dir.resolve("ci.mmd").apply { writeText("old") }
        assertFailsWith<IOException> { export(null).write(dir.resolve("ci.yml"), "x", target) }
        assertEquals("old", target.readText())
    }

    @Test
    fun `a graph without jobs fails with its note or a fallback`() {
        val target = dir.resolve("ci.mmd").apply { writeText("old") }
        val withNote =
            assertFailsWith<IOException> { export(graph("Broken", true)).write(dir.resolve("ci.yml"), "x", target) }
        assertEquals("Broken", withNote.message)
        val without =
            assertFailsWith<IOException> { export(graph(null, true)).write(dir.resolve("ci.yml"), "x", target) }
        assertEquals("No jobs in this workflow.", without.message)
        assertEquals("old", target.readText())
    }
}
