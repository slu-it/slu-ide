package dev.sluide.fileviewers.image

import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.awt.image.BufferedImage
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ExecutionException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The offscreen rasteriser of the SVG export as PNG. Needs a display (`uiTest`). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class SvgRasterizerUiTest {
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        // The rasteriser draws in a popup of a window that shows.
        this.stage = stage
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    private val rasterizer = WebViewSvgRasterizer()

    private fun rasterize(
        svg: String,
        scale: Int,
    ) = rasterizer.rasterize(svg.toByteArray(), scale)

    private fun BufferedImage.alpha(
        x: Int,
        y: Int,
    ) = getRGB(x, y) ushr 24

    private fun BufferedImage.rgb(
        x: Int,
        y: Int,
    ) = getRGB(x, y) and 0xffffff

    @Test
    fun `at 100 percent the image has the SVG's size and transparency`() {
        val image = rasterize(HALF_RED, 1)
        assertEquals(40 to 20, image.width to image.height)
        assertEquals(0xff, image.alpha(5, 5))
        assertEquals(0xff0000, image.rgb(5, 5))
        assertEquals(0, image.alpha(35, 5))
    }

    @Test
    fun `at 300 percent the image is three times as large`() {
        val image = rasterize(HALF_RED, 3)
        assertEquals(120 to 60, image.width to image.height)
        assertEquals(0xff0000, image.rgb(15, 15))
        assertEquals(0xff, image.alpha(59, 59))
        assertEquals(0, image.alpha(60, 30))
        assertEquals(0, image.alpha(105, 15))
    }

    @Test
    fun `at 300 percent a line is drawn sharp, not stretched`() {
        val line =
            "<svg xmlns='http://www.w3.org/2000/svg' width='20' height='20'>" +
                "<rect x='10' width='1' height='20' fill='black'/></svg>"
        val image = rasterize(line, 3)
        val row = (0 until image.width).map { image.alpha(it, 30) }
        assertEquals(List(30) { 0 } + List(3) { 0xff } + List(27) { 0 }, row)
    }

    @Test
    fun `an image larger than the window is drawn in full`() {
        val corner =
            "<svg xmlns='http://www.w3.org/2000/svg' width='400' height='300'>" +
                "<rect x='350' y='250' width='50' height='50' fill='red'/></svg>"
        val image = rasterize(corner, 3)
        assertEquals(1200 to 900, image.width to image.height)
        assertEquals(0xff, image.alpha(1150, 850))
        assertEquals(0xff0000, image.rgb(1150, 850))
        assertEquals(0, image.alpha(1000, 700))
    }

    @Test
    fun `an SVG with only a viewBox has a size`() {
        val image =
            rasterize(
                "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 50 30'><rect width='50' height='30'/></svg>",
                1,
            )
        assertTrue(image.width > 0 && image.height > 0, "${image.width} × ${image.height}")
    }

    @Test
    fun `text that is not an SVG fails`() {
        val failure = assertFailsWith<IOException> { rasterize("not an image", 1) }
        assertEquals("Not an SVG image", failure.message)
    }

    @Test
    fun `an image with a side that is too long fails`() {
        val wide = "<svg xmlns='http://www.w3.org/2000/svg' width='3000' height='10'/>"
        val failure = assertFailsWith<IOException> { rasterize(wide, 3) }
        assertEquals("Too large at 300%: 9000 × 30 px", failure.message)
    }

    @Test
    fun `an image with too many pixels fails`() {
        val large = "<svg xmlns='http://www.w3.org/2000/svg' width='2000' height='2000'/>"
        val failure = assertFailsWith<IOException> { rasterize(large, 3) }
        assertEquals("Too large at 300%: 6000 × 6000 px", failure.message)
    }

    @Test
    fun `without a window that shows, nothing is drawn`(robot: FxRobot) {
        robot.interact { stage.hide() }
        try {
            // Otherwise a window left open by another test would be drawn in.
            assertTrue(Window.getWindows().none { it.isShowing }, "a window still shows")
            val failure = assertFailsWith<IOException> { rasterize(HALF_RED, 1) }
            assertEquals("No window to draw in", failure.message)
        } finally {
            robot.interact { stage.show() }
        }
    }

    @Test
    fun `what is not drawn in time fails`() {
        val failure =
            assertFailsWith<IOException> {
                WebViewSvgRasterizer(
                    Duration.ofMillis(1),
                ).rasterize(HALF_RED.toByteArray(), 1)
            }
        assertEquals("Timed out", failure.message)
    }

    @Test
    fun `rasterising on the FX thread is refused`() {
        val failure =
            assertFailsWith<ExecutionException> {
                WaitForAsyncUtils.asyncFx<BufferedImage> { rasterize(HALF_RED, 1) }.get()
            }
        assertIs<IllegalStateException>(failure.cause)
    }

    @Test
    fun `an image drawn in tiles equals the image drawn in one`() {
        for (scale in listOf(1, 3)) {
            val whole = rasterize(SHAPES, scale)
            val tiled = WebViewSvgRasterizer(tile = 64).rasterize(SHAPES.toByteArray(), scale)
            assertEquals(whole.width to whole.height, tiled.width to tiled.height)
            // Prism's rasteriser clips a path to the tile before flattening its curves, so that the antialiased edge
            // of a curve differs a little (up to 21 of 255 here; equal with -Dprism.marlin.clip=false). Compared
            // premultiplied: the colour of an almost transparent pixel says nothing.
            val difference = whole.premultiplied().zip(tiled.premultiplied()).maxOf { (a, b) -> abs(a - b) }
            assertTrue(difference <= 32, "the tiles differ by $difference at ${scale * 100}%")
        }
    }

    @Test
    fun `the tiles at the right and bottom edges are drawn in full`() {
        // 150 × 100 in tiles of 64: three columns (64, 64, 22) and two rows (64, 36), each tile in its own colour.
        val colors = listOf(0xff0000, 0x00ff00, 0x0000ff, 0xffff00, 0x00ffff, 0xff00ff)
        val tiles =
            colors.withIndex().joinToString("") { (i, color) ->
                "<rect x='${i % 3 * 64}' y='${i / 3 * 64}' width='64' height='64' fill='#%06x'/>".format(color)
            }
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='150' height='100'>$tiles</svg>"
        val image = WebViewSvgRasterizer(tile = 64).rasterize(svg.toByteArray(), 1)
        assertEquals(150 to 100, image.width to image.height)
        val points = listOf(10 to 10, 100 to 10, 140 to 10, 10 to 90, 100 to 90, 149 to 99)
        assertEquals(colors, points.map { (x, y) -> image.rgb(x, y) })
        assertTrue(points.all { (x, y) -> image.alpha(x, y) == 0xff })
    }

    @Test
    fun `a transparent tile next to drawn ones stays transparent`() {
        val right =
            "<svg xmlns='http://www.w3.org/2000/svg' width='150' height='100'>" +
                "<rect x='75' width='75' height='100' fill='red'/></svg>"
        val image = WebViewSvgRasterizer(tile = 64).rasterize(right.toByteArray(), 1)
        assertTrue((0 until 75).all { x -> (0 until 100).all { y -> image.alpha(x, y) == 0 } }, "drawn on the left")
        assertTrue(
            (75 until 150).all { x -> (0 until 100).all { y -> image.getRGB(x, y) == 0xffff0000.toInt() } },
            "not drawn on the right",
        )
    }

    /** The image's channels, premultiplied with its alpha. */
    private fun BufferedImage.premultiplied() =
        getRGB(0, 0, width, height, null, 0, width).flatMap { argb ->
            val alpha = argb ushr 24
            listOf(alpha) + listOf(16, 8, 0).map { (argb shr it and 0xff) * alpha / 0xff }
        }

    private companion object {
        /** 40 × 20, the left half red, the right half transparent. */
        const val HALF_RED =
            "<svg xmlns='http://www.w3.org/2000/svg' width='40' height='20'><rect width='20' height='20' fill='red'/></svg>"

        /** 150 × 100: shapes, text-free, and a stroked diagonal crossing the borders of tiles of 64. */
        const val SHAPES =
            "<svg xmlns='http://www.w3.org/2000/svg' width='150' height='100'>" +
                "<rect x='5' y='5' width='80' height='40' fill='#3366cc'/>" +
                "<circle cx='100' cy='60' r='35' fill='#cc3333' fill-opacity='0.7'/>" +
                "<ellipse cx='40' cy='80' rx='30' ry='15' fill='#33aa55'/>" +
                "<line x1='0' y1='0' x2='150' y2='100' stroke='black' stroke-width='3'/></svg>"
    }
}
