package dev.sluide.previews.preview

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportPlanTest {
    private val a = Path("/x/a.svg")
    private val b = Path("/x/a-2.svg")
    private val c = Path("/x/a-3.svg")

    private fun plan(
        targets: List<Path>,
        existing: Set<Path>,
        fallback: Path = Path("/x/fallback.svg"),
    ) = planExport(targets, { fallback }, existing::contains)

    @Test
    fun `the first target is written for, the existing ones are asked about`() {
        assertEquals(ExportPlan(a, listOf(b, c)), plan(listOf(a, b, c), setOf(b, c)))
        assertEquals(ExportPlan(a, emptyList()), plan(listOf(a, b), emptySet()))
    }

    @Test
    fun `without targets the export's own target is the fallback and nothing is asked`() {
        assertEquals(ExportPlan(Path("/x/fallback.svg"), emptyList()), plan(emptyList(), setOf(a)))
    }

    @Test
    fun `the message with problems names the file, or for several files the source, without counting them`() {
        val source = Path("/x/diagram.puml")
        assertEquals("Exported a.svg, with problems:\nP\nQ", exportedMessage(source, a, 1, listOf("P", "Q")))
        // Two of four diagrams failed to be written: no count of files exported can be told from here.
        assertEquals(
            "Exported the files of diagram.puml, with problems:\nDiagram 2 not exported: X\nDiagram 3 not exported: Y",
            exportedMessage(source, a, 4, listOf("Diagram 2 not exported: X", "Diagram 3 not exported: Y")),
        )
    }

    @Test
    fun `without an exists function the files that are there are asked about`(
        @TempDir dir: Path,
    ) {
        val first = dir.resolve("a.svg").apply { writeText("old") }
        val second = dir.resolve("a-2.svg")
        assertEquals(ExportPlan(first, listOf(first)), planExport(listOf(first, second), { error("unused") }))
    }
}
