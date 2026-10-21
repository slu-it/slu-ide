package dev.sluide.fileviewers

import dev.sluide.fileviewers.bicep.BicepGraph
import dev.sluide.fileviewers.bicep.MAIN_BICEP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BicepResourceGraphTest {
    @Test
    fun `the graph of a file is its Mermaid diagram`() {
        val graph = BicepResourceGraph.of(MAIN_BICEP)

        assertEquals(BicepGraph.parse(MAIN_BICEP).toMermaid(), graph.mermaid)
        assertFalse(graph.isEmpty)
        assertNull(graph.note)
    }

    @Test
    fun `a file without resources or modules has an empty graph`() {
        assertTrue(BicepResourceGraph.of("param p string\n// resource x 'A' = {}\n").isEmpty)
    }

    @Test
    fun `a graph that is cut has a note`() {
        val many = (0 until 201).joinToString("\n") { "resource r$it 'A' = {}" }

        assertEquals("Showing the first 200 of 201 resources and modules.", BicepResourceGraph.of(many).note)
    }
}
