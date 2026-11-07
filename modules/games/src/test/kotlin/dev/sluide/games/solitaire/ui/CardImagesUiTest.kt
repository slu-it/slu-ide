package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Card
import dev.sluide.games.solitaire.Suit
import javafx.scene.image.Image
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Rendering the cards needs the toolkit (canvas snapshots). Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class CardImagesUiTest {
    private val robot = FxRobot()

    private fun onFx(block: () -> Unit) = robot.interact(block)

    @Start
    fun start(stage: Stage) {
        // the extension starts the toolkit; no stage content is needed
    }

    private val all = Suit.entries.flatMap { s -> (1..13).map { Card(it, s) } }

    @Test
    fun `all faces and the back render at both sizes in both themes with the expected pixel size`() {
        onFx {
            for (dark in listOf(false, true)) {
                for (width in listOf(48.0, 120.0)) {
                    for (scale in listOf(1.0, 2.0)) {
                        val images = CardImages(width, dark, scale)
                        val w = ceil(width * scale)
                        val h = ceil(images.height * scale)
                        for (image in all.map { images.face(it) } + images.back()) {
                            assertEquals(w, image.width)
                            assertEquals(h, image.height)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `different cards give different pixels and the images are cached`() {
        onFx {
            val images = CardImages(120.0, dark = false)
            val ace = images.face(Card(1, Suit.SPADES))
            val king = images.face(Card(13, Suit.HEARTS))
            assertNotEquals(pixels(ace), pixels(king))
            assertNotEquals(pixels(images.back()), pixels(ace))
            assertSame(ace, images.face(Card(1, Suit.SPADES)))
            assertSame(images.back(), images.back())
        }
    }

    @Test
    fun `the corner is transparent outside the rounded rectangle and the middle of a face is opaque`() {
        onFx {
            val reader = CardImages(120.0, dark = true).face(Card(1, Suit.CLUBS)).pixelReader
            assertEquals(0.0, reader.getColor(0, 0).opacity)
            assertTrue(reader.getColor(60, 5).opacity > 0.9)
        }
    }

    @Test
    fun `the scale paints the card over the whole larger image`() {
        onFx {
            val images = CardImages(60.0, dark = false, scale = 2.0)
            val image = images.face(Card(5, Suit.HEARTS))
            val x = ceil(120.0).toInt() - 4
            val y = ceil(images.height * 2).toInt() / 2
            assertTrue(image.pixelReader.getColor(x, y).opacity > 0.9)
        }
    }

    @Test
    fun `the theme changes the back`() {
        onFx {
            assertNotEquals(pixels(CardImages(60.0, false).back()), pixels(CardImages(60.0, true).back()))
        }
    }

    private fun pixels(image: Image): List<Int> {
        val reader = image.pixelReader
        return (0 until image.height.toInt() step 3).flatMap { y ->
            (0 until image.width.toInt() step 3).map { reader.getArgb(it, y) }
        }
    }
}
