package dev.sluide.barcodes.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

internal class ExportTest {
    private val black = 0xFF000000.toInt()
    private val white = -1

    private fun code(
        text: String,
        format: CodeFormat,
    ) = Generator.generate(text, format, GenerateOptions()) as Generated.Code

    @Test
    fun `PNG has whole-pixel modules inside the quiet zone`() {
        // "Hello" is QR version 1: 21 modules plus 2 × 4 quiet = 29, so 512 / 29 = 17 px per module.
        val png = Export.png(code("Hello", CodeFormat.QR_CODE), GenerateOptions())
        val image = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(29 * 17, image.width)
        assertEquals(29 * 17, image.height)
        assertEquals(white, image.getRGB(0, 0))
        val quiet = 4 * 17
        assertEquals(white, image.getRGB(quiet - 1, quiet))
        assertEquals(black, image.getRGB(quiet, quiet), "first finder module")
        assertEquals(black, image.getRGB(quiet + 16, quiet + 16), "the module ends after 17 px")
        assertEquals(white, image.getRGB(quiet + 17, quiet + 17), "the finder's light ring")
    }

    @Test
    fun `quiet zone and colours come from the options`() {
        val options = GenerateOptions(sizePx = 21, quietZone = 0, foreground = 0xFF102030.toInt(), background = 0)
        val image = Export.argb(code("Hello", CodeFormat.QR_CODE), options)
        assertEquals(21, image.width)
        assertEquals(0xFF102030.toInt(), image.argb[0])
        assertEquals(0, image.argb[7], "transparent background")
    }

    @Test
    fun `a tiny size still draws one pixel per module`() {
        val image = Export.argb(code("Hello", CodeFormat.QR_CODE), GenerateOptions(sizePx = 10))
        assertEquals(29, image.width)
    }

    @Test
    fun `an image over 16 384 px per side is refused`() {
        val code = code("Hello", CodeFormat.QR_CODE)
        // 29 modules at 565 px each is 16 385 px.
        assertThrows<IllegalArgumentException> { Layout.of(code, GenerateOptions(sizePx = 29 * 565)) }
        assertEquals(29 * 564, Layout.of(code, GenerateOptions(sizePx = 29 * 564 + 28)).widthPx)
    }

    @Test
    fun `linear code has the quiet zone left and right and full-height bars`() {
        // EAN-13: 95 modules plus 2 × 10 quiet = 115, so 512 / 115 = 4 px; 0.4 × 115 = 46 modules high.
        val image = Export.argb(code("400638133393", CodeFormat.EAN_13), GenerateOptions())
        assertEquals(115 * 4, image.width)
        assertEquals(46 * 4, image.height)
        val firstBar = 10 * 4
        assertEquals(white, image.argb[firstBar - 1])
        assertEquals(black, image.argb[firstBar], "the start bar reaches the top")
        assertEquals(black, image.argb[(image.height - 1) * image.width + firstBar], "and the bottom")
    }

    @Test
    fun `SVG counts in modules and has the PNG's pixel size`() {
        val svg = Export.svg(code("Hello", CodeFormat.QR_CODE), GenerateOptions(background = 0x80FFFFFF.toInt()))
        val root =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(svg.toByteArray()))
                .documentElement
        assertEquals("svg", root.tagName)
        assertEquals("0 0 29 29", root.getAttribute("viewBox"))
        assertEquals("493", root.getAttribute("width"))
        assertEquals("493", root.getAttribute("height"))
        assertEquals("crispEdges", root.getAttribute("shape-rendering"))
        val rect = root.getElementsByTagName("rect").item(0) as org.w3c.dom.Element
        assertEquals("#ffffff", rect.getAttribute("fill"))
        assertEquals("0.502", rect.getAttribute("fill-opacity"))
        val path = root.getElementsByTagName("path").item(0) as org.w3c.dom.Element
        assertEquals("#000000", path.getAttribute("fill"))
        assertEquals("", path.getAttribute("fill-opacity"))
        // The top row of the first finder pattern is one run of 7 modules at the quiet zone.
        assertTrue(path.getAttribute("d").startsWith("M4 4h7v1h-7z"), path.getAttribute("d"))
    }

    @Test
    fun `SVG bars of a linear code run full height`() {
        val svg = Export.svg(code("400638133393", CodeFormat.EAN_13), GenerateOptions())
        assertTrue(svg.contains("viewBox=\"0 0 115 46\""), svg)
        assertTrue(svg.contains("d=\"M10 0h1v46h-1z"), svg)
    }

    @Test
    fun `colour warnings for light-on-dark and low contrast`() {
        assertNull(colorWarning(black, white))
        assertEquals("Many scanners cannot read light-on-dark codes.", colorWarning(white, black))
        assertEquals("Low contrast (2.6:1); scanners may fail.", colorWarning(0xFFA0A0A0.toInt(), white))
        assertEquals("Low contrast (1.0:1); scanners may fail.", colorWarning(0x00777777, 0xFF777777.toInt()))
    }
}
