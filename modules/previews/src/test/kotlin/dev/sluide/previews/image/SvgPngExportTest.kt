package dev.sluide.previews.image

import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SvgPngExportTest {
    @TempDir
    lateinit var dir: Path

    /** What the fake rasteriser was asked for. */
    private val asked = mutableListOf<Pair<ByteArray, PngSize>>()

    /** Draws a transparent image of 4 × 3 pixels per scale, with one red pixel. */
    private val rasterizer =
        SvgRasterizer { svg, size ->
            asked += svg to size
            val (width, height) = size.pixels(4, 3)
            BufferedImage(
                width.toInt(),
                height.toInt(),
                BufferedImage.TYPE_INT_ARGB,
            ).apply { setRGB(0, 0, 0xffff0000.toInt()) }
        }

    @Test
    fun `the items are named after the scale`() {
        assertEquals(
            listOf("PNG 100%", "PNG 200%", "PNG 300%"),
            listOf(1, 2, 3).map { SvgPngExport(PngSize.Scale(it)).label },
        )
    }

    @Test
    fun `the PNGs are named after the file with the scale as suffix`() {
        val targets = listOf(1, 2, 3).map { SvgPngExport(PngSize.Scale(it), rasterizer).target(Path("/a/logo.svg")) }
        assertEquals(listOf(Path("/a/logo.png"), Path("/a/logo@2x.png"), Path("/a/logo@3x.png")), targets)
        assertEquals(Path("/a/Logo@2x.png"), SvgPngExport(PngSize.Scale(2), rasterizer).target(Path("/a/Logo.SVG")))
    }

    @Test
    fun `writing puts the drawn image into the target as PNG`() {
        val file = dir.resolve("logo.svg").apply { writeText("<svg/>") }
        val target = dir.resolve("logo@2x.png").apply { writeText("old") }
        val problems = SvgPngExport(PngSize.Scale(2), rasterizer).write(file, "<svg/>", target)
        assertEquals(emptyList(), problems)
        assertEquals(listOf("<svg/>" to PngSize.Scale(2)), asked.map { it.first.decodeToString() to it.second })
        assertEquals(listOf(file, target), dir.listDirectoryEntries().sorted())
        val image = assertNotNull(ImageIO.read(target.toFile()))
        assertEquals(8 to 6, image.width to image.height)
        assertEquals(0xffff0000.toInt(), image.getRGB(0, 0))
        assertEquals(0, image.getRGB(1, 0) ushr 24)
    }

    @Test
    fun `a size in pixels is named with width and height`() {
        val export = SvgPngExport(PngSize.Pixels(32, 32), rasterizer)
        assertEquals(Path("/a/logo-32x32.png"), export.target(Path("/a/logo.svg")))
        assertEquals(Path("/a/Logo-32x32.png"), export.target(Path("/a/Logo.SVG")))
        assertEquals("PNG 32 × 32 px", export.label)
    }

    @Test
    fun `writing at a size in pixels draws exactly that size`() {
        val file = dir.resolve("logo.svg").apply { writeText("<svg/>") }
        val target = dir.resolve("logo-32x16.png")
        SvgPngExport(PngSize.Pixels(32, 16), rasterizer).write(file, "<svg/>", target)
        assertEquals(listOf<PngSize>(PngSize.Pixels(32, 16)), asked.map { it.second })
        val image = assertNotNull(ImageIO.read(target.toFile()))
        assertEquals(32 to 16, image.width to image.height)
    }

    @Test
    fun `the saved file is drawn in its own encoding`() {
        val svg = "<?xml version='1.0' encoding='ISO-8859-1'?><svg><text>Grüße</text></svg>"
        val bytes = svg.toByteArray(Charsets.ISO_8859_1)
        val file = dir.resolve("logo.svg").apply { writeBytes(bytes) }
        SvgPngExport(PngSize.Scale(1), rasterizer).write(file, svg, dir.resolve("logo.png"))
        assertContentEquals(bytes, asked.single().first)
    }
}
