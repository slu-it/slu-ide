package dev.sluide.githubactions.mermaid

import kotlin.test.Test
import kotlin.test.assertEquals

class MermaidLabelsTest {
    @Test
    fun `everything but letters, digits and a few signs becomes an entity code`() {
        assertEquals("a_b-c./:@(), 1", MermaidLabels.label("a_b-c./:@(), 1"))
        assertEquals("#34;#93; --#62; #37;#37; #60;script#62;", MermaidLabels.label("\"] --> %% <script>"))
        assertEquals("#96;x#96; #35;35#59; #228;#128512;", MermaidLabels.label("`x` #35; ä😀"))
        assertEquals("a  b", MermaidLabels.label("a\n\tb"))
    }

    @Test
    fun `long labels are cut at 48 code points or 96 characters`() {
        val long = "x".repeat(60)

        assertEquals("x".repeat(47) + "#8230;", MermaidLabels.label(long))
        assertEquals("#8230;" + "x".repeat(47), MermaidLabels.label(long, keepEnd = true))
        assertEquals("#60;".repeat(22) + "#8230;", MermaidLabels.label("<".repeat(30)))
    }
}
