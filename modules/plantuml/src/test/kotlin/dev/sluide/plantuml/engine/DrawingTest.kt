package dev.sluide.plantuml.engine

import dev.sluide.plantuml.Diagram
import dev.sluide.plantuml.GraphvizLocator
import dev.sluide.plantuml.Rendering
import dev.sluide.plantuml.onlyDrawn
import dev.sluide.testsupport.LogCapture
import net.sourceforge.plantuml.security.SecurityUtils
import net.sourceforge.plantuml.utils.LineLocation
import net.sourceforge.plantuml.utils.LineLocationImpl
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.logging.Level
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DrawingTest {
    private fun draw(
        source: String,
        firstLine: Int = 1,
    ) = Drawing.draw(source.trimIndent() + "\n", firstLine, null)

    private fun Rendering.drawn() = diagrams.map { it as Diagram.Drawn }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        textBlock = """
        ""                                                   | 0
        "prose\nAlice -> Bob : hi"                           | 0
        "prose\n@startuml\nAlice -> Bob : hi\n@enduml\nmore" | 1""",
    )
    fun `only @start blocks are drawn, prose around them is not`(
        source: String,
        diagrams: Int,
    ) {
        val rendering = draw(source.replace("\\n", "\n"))

        assertEquals(diagrams, rendering.diagrams.size)
        assertTrue(rendering.diagrams.all { it is Diagram.Drawn })
    }

    @Test
    fun `every block is a diagram, in source order`() {
        val drawn =
            draw(
                """
                @startuml
                Alice -> Bob : FIRSTBLOCK
                @enduml
                text between
                @startuml
                Bob -> Carol : SECONDBLOCK
                @enduml
                """,
            ).drawn()

        assertEquals(2, drawn.size)
        assertTrue("FIRSTBLOCK" in drawn[0].lightSvg && "SECONDBLOCK" !in drawn[0].lightSvg)
        assertTrue("SECONDBLOCK" in drawn[1].lightSvg && "FIRSTBLOCK" !in drawn[1].lightSvg)
    }

    @Test
    fun `every page of a block is a diagram of its own`() {
        val drawn =
            draw(
                """
                @startuml
                Alice -> Bob : PAGEONE
                newpage
                Alice -> Bob : PAGETWO
                @enduml
                @startuml
                Alice -> Bob : NEXTBLOCK
                @enduml
                """,
            ).drawn()

        assertEquals(3, drawn.size)
        assertTrue("PAGEONE" in drawn[0].lightSvg && "PAGETWO" !in drawn[0].lightSvg)
        assertTrue("PAGETWO" in drawn[1].lightSvg && "PAGEONE" !in drawn[1].lightSvg)
        assertTrue("PAGETWO" in drawn[1].darkSvg && "PAGEONE" !in drawn[1].darkSvg)
        assertTrue("NEXTBLOCK" in drawn[2].lightSvg)
    }

    @Test
    fun `mind maps and JSON blocks are drawn`() {
        val drawn =
            draw(
                """
                @startmindmap
                * MAPROOT
                ** MAPCHILD
                @endmindmap
                @startjson
                {"JSONKEY": [1, 2]}
                @endjson
                """,
            ).drawn()

        assertEquals(2, drawn.size)
        assertTrue("MAPROOT" in drawn[0].lightSvg && "MAPCHILD" in drawn[0].lightSvg)
        assertTrue("JSONKEY" in drawn[1].lightSvg)
    }

    @Test
    fun `the dark variant has PlantUML's dark background, the light one not`() {
        // Measured: DARK_MODE paints the background #1B1B1B; light and dark exports of one parsed diagram equal fresh
        // parses.
        val diagram = draw("@startuml\nAlice -> Bob : hi\n@enduml").onlyDrawn()

        assertTrue("background:#1B1B1B" in diagram.darkSvg)
        assertFalse("1B1B1B" in diagram.lightSvg)
        assertNotEquals(diagram.lightSvg, diagram.darkSvg)
    }

    @Test
    fun `a light export after a dark one equals the light export before it`() {
        val source = "@startuml\nclass A\nA --> B\n@enduml"

        val before = draw(source).onlyDrawn()
        val after = draw(source).onlyDrawn()

        assertEquals(before.lightSvg, after.lightSvg)
        assertEquals(before.darkSvg, after.darkSvg)
    }

    @ParameterizedTest(name = "[{index}] firstLine {0}")
    @CsvSource("1, 7", "20, 26")
    fun `a syntax error has PlantUML's message and the line in the caller's numbering`(
        firstLine: Int,
        line: Int,
    ) {
        // Measured: the position is 0-based over the whole source, not the block.
        val rendering =
            draw(
                """
                @startuml
                Alice -> Bob : ok
                @enduml

                @startuml
                Alice -> Bob : fine
                this is not valid syntax
                Bob -> Alice
                @enduml
                """,
                firstLine,
            )

        assertEquals(2, rendering.diagrams.size, rendering.toString())
        assertEquals(Diagram.Failed("Syntax Error? (Assumed diagram type: sequence)", line), rendering.diagrams[1])
        assertTrue(rendering.diagrams[0] is Diagram.Drawn)
    }

    @Test
    fun `an error inside a stdlib file names the file and its line, and has no line of the source`() {
        // Measured: this error's location is <C4/C4_Container> line 72 without a parent location, so no line of the
        // source can be named.
        val rendering =
            draw(
                """
                @startuml
                !include <C4/C4_Container>
                Container(a, "b", "c", ${'$'}sprite="x", ${'$'}link="y", ${'$'}tags="t+u", ${'$'}baseShape="nope")
                Rel(a, b, "x", ${'$'}tags=1+)
                @enduml
                """,
            )

        assertEquals(
            listOf(
                Diagram.Failed("Syntax Error? (Assumed diagram type: sequence) (in <C4/C4_Container>, line 72)", null),
            ),
            rendering.diagrams,
        )
    }

    @Test
    fun `an error in an included file names it and has the line of the include`() {
        // The location chain PlantUML gives an error in a local include, measured under the ALLOWLIST profile: the
        // included file at its line, its parent the source at the include's line. SANDBOX refuses local includes, so
        // the chain is built with PlantUML's own LineLocationImpl.
        val included = location("sub/broken.iuml", 1, location("string", 43))

        assertEquals(
            Diagram.Failed("Syntax Error? (in sub/broken.iuml, line 2)", 63),
            Drawing.failure("Syntax Error?", included, 20),
        )
    }

    @Test
    fun `an error without a location has no line`() {
        assertEquals(Diagram.Failed("Syntax Error?", null), Drawing.failure("Syntax Error?", null, 20))
    }

    /** A location at the 0-based [position], as PlantUML's reader makes it: it starts before the first line. */
    private fun location(
        description: String,
        position: Int,
        parent: LineLocation? = null,
    ): LineLocation = (0..position).fold(LineLocationImpl(description, parent)) { it, _ -> it.oneLineRead() }

    @Test
    fun `quotes and markup in labels come out as text, never as elements`() {
        val drawn =
            draw(
                """
                @startuml
                class "<script>alert(1)</script> & \"q\" 's'" as A
                class "x' onload='alert(2)" as B
                A : field "x" onerror="alert(3)" & y
                note "</text><script>n</script>" as N
                @enduml
                """,
            ).onlyDrawn()

        for (svg in listOf(drawn.lightSvg, drawn.darkSvg)) {
            // Parsing proves the escaping: markup in a label would add elements or attributes, or break the XML.
            val root = parse(svg)
            val elements = root.getElementsByTagName("*")
            val attributes =
                (0 until elements.length).flatMap { i ->
                    val element = elements.item(i)
                    (0 until element.attributes.length).map { element.attributes.item(it).nodeName }
                }
            assertEquals(0, root.getElementsByTagName("script").length, svg)
            assertTrue(attributes.none { it.startsWith("on") }, attributes.toString())
            assertTrue("<script>alert(1)</script> & \\\"q\\\" 's'" in root.textContent, root.textContent)
            assertTrue("x' onload='alert(2)" in root.textContent, root.textContent)
            assertTrue("field \"x\" onerror=\"alert(3)\" & y" in root.textContent, root.textContent)
            assertTrue("</text><script>n</script>" in root.textContent, root.textContent)
        }
    }

    @Test
    fun `a draw given up stops before its next export`() {
        val source = "@startuml\nAlice -> Bob : one\n@enduml\n@startuml\nAlice -> Bob : two\n@enduml\n"
        var asked = 0

        val stopped = Drawing.draw(source, 1, null) { asked++ >= 1 }

        // Asked before the first light export (go on) and before its dark export (stop): nothing is complete.
        assertEquals(Rendering(emptyList(), false), stopped)
        assertEquals(2, asked)
        assertEquals(2, Drawing.draw(source, 1, null) { false }.diagrams.size)
    }

    @Test
    fun `a draw given up from the start stops before its first export`() {
        var asked = 0

        val stopped = Drawing.draw("@startuml\nAlice -> Bob : one\n@enduml\n", 1, null) { asked++ >= 0 }

        // Asked once: it stopped at the first answer, before the light export it would ask about the dark one after.
        assertEquals(Rendering(emptyList(), false), stopped)
        assertEquals(1, asked)
    }

    @Test
    fun `a PlantUML profile other than SANDBOX refuses to draw`() {
        SecurityUtils.setCommandLineProperty("PLANTUML_SECURITY_PROFILE", "LEGACY")
        val (rendering, log) =
            try {
                LogCapture(Drawing::class.java.name).use { log ->
                    Drawing.draw("@startuml\nAlice -> Bob : hi\n@enduml\n", 1, null) to log.lines.toList()
                }
            } finally {
                Drawing.enterSandbox()
            }

        assertEquals(Rendering(listOf(Diagram.Failed("PlantUML is not in its sandbox", null)), false), rendering)
        assertEquals(listOf(Level.WARNING to "PlantUML is in its LEGACY profile, not SANDBOX: nothing is drawn"), log)
    }

    @Test
    fun `a source over 512 KiB in UTF-8 is not drawn, one of exactly 512 KiB is`() {
        val limit = 512 * 1024
        // "ä" is two bytes in UTF-8: the over-long source has fewer than 512 Ki characters.
        val over = sourceOfBytes(limit + 1, "ä")
        val exact = sourceOfBytes(limit, "ä")
        assertTrue(over.length < limit)

        assertEquals(
            Rendering(listOf(Diagram.Failed("Too large to draw: 513 KiB, at most 512 KiB", null)), false),
            Drawing.draw(over, 1, null),
        )
        Drawing.draw(exact, 1, null).onlyDrawn()
    }

    /** A sequence diagram of exactly [bytes] UTF-8 bytes, padded with comment lines of [filler]. */
    private fun sourceOfBytes(
        bytes: Int,
        filler: String,
    ): String {
        val head = "@startuml\nAlice -> Bob : hi\n"
        val tail = "@enduml\n"
        val text = StringBuilder(head)
        val line = "' " + filler.repeat(40) + "\n"
        var used = size(head) + size(tail)
        while (used + size(line) <= bytes) {
            text.append(line)
            used += size(line)
        }
        // A blank line takes the rest.
        if (used < bytes) text.append(" ".repeat(bytes - used - 1)).append('\n')
        text.append(tail)
        assertEquals(bytes, size(text))
        return text.toString()
    }

    private fun size(text: CharSequence) = text.toString().toByteArray(Charsets.UTF_8).size

    @Test
    fun `without Graphviz a class diagram is laid out by Smetana and says so`() {
        // Measured: a class diagram requires the Graphviz engine until Smetana is forced. The same diagram with PlantUML's own Smetana pragma draws the same SVG, but
        // for the encoded source PlantUML puts into it.
        val rendering = draw("@startuml\nclass A\nclass B\nA --> B\n@enduml")
        val pragma = draw("@startuml\nclass A\nclass B\nA --> B\n!pragma layout smetana\n@enduml")

        assertTrue(rendering.builtInLayout)
        assertEquals(withoutSource(pragma.onlyDrawn().lightSvg), withoutSource(rendering.onlyDrawn().lightSvg))
    }

    private fun withoutSource(svg: String) = svg.replace(Regex("""<\?plantuml-src [^?]*\?>"""), "")

    @Test
    fun `without Graphviz diagrams that need no layout engine are drawn without a note`() {
        // The ditaa block draws PlantUML's notice that the MIT build has no ditaa: a diagram that is no TitledDiagram,
        // so Smetana cannot be set on it.
        val rendering =
            draw(
                "@startuml\nAlice -> Bob : hi\n@enduml\n@startmindmap\n* root\n@endmindmap\n@startditaa\n+--+\n@endditaa",
            )

        assertFalse(rendering.builtInLayout)
        assertEquals(3, rendering.drawn().size)
    }

    @Test
    fun `with Graphviz a class diagram is laid out by dot and needs no note about the layout`() {
        // Runs only where dot is installed (the sandbox has /usr/bin/dot, CI's macOS runner may not): ADR 0193.
        val dot = GraphvizLocator({ null }).locate()
        assumeTrue(dot != null, "Graphviz's dot is not installed")
        val source = "@startuml\nclass A\nclass B\nA --> B\n@enduml\n"

        val rendering = Drawing.draw(source, 1, dot)
        val smetana = Drawing.draw(source, 1, null)

        assertFalse(rendering.builtInLayout)
        // dot places the classes elsewhere than Smetana does.
        assertNotEquals(smetana.onlyDrawn().lightSvg, rendering.onlyDrawn().lightSvg)
    }

    private fun parse(svg: String): Element {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(svg.toByteArray())).documentElement
    }
}
