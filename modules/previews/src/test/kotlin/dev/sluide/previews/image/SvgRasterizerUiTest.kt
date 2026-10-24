package dev.sluide.previews.image

import javafx.collections.ListChangeListener
import javafx.scene.Node
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
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
        // The rasteriser draws in the root of a window that shows.
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
    fun `the image is drawn in the window, which is left as it was`() {
        val windows = mutableListOf<Window>()
        val listener = ListChangeListener<Window> { change -> while (change.next()) windows += change.addedSubList }
        WaitForAsyncUtils.asyncFx { Window.getWindows().addListener(listener) }.get()
        try {
            rasterize(HALF_RED, 1)
            WaitForAsyncUtils.waitForFxEvents()
        } finally {
            WaitForAsyncUtils.asyncFx { Window.getWindows().removeListener(listener) }.get()
        }
        // A window of its own (a popup off screen) crashed JavaFX's Metal pipeline with displays of different scales.
        assertEquals(emptyList(), windows, "a window was opened")
        assertEquals(
            emptyList(),
            WaitForAsyncUtils
                .asyncFx<List<Node>> { (stage.scene.root as StackPane).childrenUnmodifiable.toList() }
                .get(),
            "the view stayed in the root",
        )
    }

    @Test
    fun `text that is not an SVG fails`() {
        val failure = assertFailsWith<IOException> { rasterize("not an image", 1) }
        assertEquals("Not an SVG image", failure.message)
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
    fun `a window that closes during the drawing fails it`(robot: FxRobot) {
        val root = stage.scene.root as StackPane
        // Large enough to take more than one pulse, transparent so that each tile takes all of them.
        val large = "<svg xmlns='http://www.w3.org/2000/svg' width='300' height='300'/>"
        val drawing =
            CompletableFuture.supplyAsync {
                WebViewSvgRasterizer(
                    tile = 64,
                ).rasterize(large.toByteArray(), 1)
            }
        try {
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
                WaitForAsyncUtils.asyncFx<Boolean> { root.childrenUnmodifiable.isNotEmpty() }.get()
            }
            robot.interact { stage.hide() }
            val failure = assertFailsWith<ExecutionException> { drawing.get(10, TimeUnit.SECONDS) }
            assertEquals("The window closed", failure.cause?.message)
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
        // At 300% the tiles are larger, to keep the number of snapshots down; their borders are crossed all the same.
        for ((scale, tile) in listOf(1 to 64, 3 to 128)) {
            val whole = rasterize(SHAPES, scale)
            val tiled = WebViewSvgRasterizer(tile = tile).rasterize(SHAPES.toByteArray(), scale)
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
        // 128 × 64 in tiles of 64: the left tile is blank, the right one is drawn from x = 75 on.
        val right =
            "<svg xmlns='http://www.w3.org/2000/svg' width='128' height='64'>" +
                "<rect x='75' width='53' height='64' fill='red'/></svg>"
        val image = WebViewSvgRasterizer(tile = 64).rasterize(right.toByteArray(), 1)
        assertTrue((0 until 75).all { x -> (0 until 64).all { y -> image.alpha(x, y) == 0 } }, "drawn on the left")
        assertTrue(
            (75 until 128).all { x -> (0 until 64).all { y -> image.getRGB(x, y) == 0xffff0000.toInt() } },
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
