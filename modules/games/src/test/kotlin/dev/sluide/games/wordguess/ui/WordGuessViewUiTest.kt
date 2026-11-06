package dev.sluide.games.wordguess.ui

import dev.sluide.games.wordguess.KeyboardKey
import dev.sluide.games.wordguess.Language
import dev.sluide.games.wordguess.WordGuessModel
import dev.sluide.games.wordguess.WordGuessPreferences
import dev.sluide.games.wordguess.WordList
import dev.sluide.projectdata.ProjectDataStore
import javafx.event.Event
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Each connection of the Word Guess view to its model needs a scene: key events with the focus, the colours that
 * come from style classes, clicks on the on-screen keys and the language menu. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class WordGuessViewUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var model: WordGuessModel
    private lateinit var view: WordGuessView
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = WordGuessPreferences(store, Duration.ZERO, Executor { it.run() })
        val english = WordList(listOf("CRANE"), setOf("CRANE", "SLATE"))
        val german = WordList(listOf("BÜHNE"), setOf("BÜHNE"))
        model = WordGuessModel(preferences, { if (it == Language.EN) english else german }, Random(1))
        view = WordGuessView(model)
        stage.scene = Scene(view.node as Parent, 600.0, 700.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(block).get()

    private fun typeKeys(
        robot: FxRobot,
        text: String,
    ) = robot.interact {
        text.forEach {
            Event.fireEvent(
                view.grid,
                KeyEvent(KeyEvent.KEY_TYPED, "$it", "", KeyCode.UNDEFINED, false, false, false, false),
            )
        }
    }

    private fun press(
        robot: FxRobot,
        code: KeyCode,
    ) = robot.interact {
        Event.fireEvent(view.grid, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
    }

    private fun tiles(row: Int) = fx { (view.grid.children[row] as HBox).children.map { it as Label } }

    private fun statusOf(row: Int) = tiles(row).map { l -> fx { l.styleClass.last() } }

    @Test
    fun `a guess colours the tiles of its row and the keys of its letters`(robot: FxRobot) {
        typeKeys(robot, "slate")
        assertEquals(listOf("wg-typed"), fx { tiles(0)[0].styleClass.drop(1) })

        press(robot, KeyCode.ENTER)

        // CRANE against SLATE: A and E are in place, nothing else is in the word.
        assertEquals(
            listOf("wg-absent", "wg-absent", "wg-correct", "wg-absent", "wg-correct"),
            statusOf(0),
        )
        assertTrue("wg-correct" in fx { view.keyboard.keyOf('A')!!.styleClass })
        assertTrue("wg-absent" in fx { view.keyboard.keyOf('S')!!.styleClass })
        assertTrue("wg-unknown" in fx { view.keyboard.keyOf('Q')!!.styleClass })
    }

    @Test
    fun `clicking an on-screen key types and leaves the focus on the grid`(robot: FxRobot) {
        robot.interact { view.languageMenu.requestFocus() }

        robot.interact { view.keyboard.keyOf('Q')!!.fire() }

        assertEquals("Q", fx { model.input })
        assertSame(view.grid, fx { stage.scene.focusOwner })
        assertFalse(fx { view.keyboard.keyOf('Q')!!.isFocusTraversable })
    }

    @Test
    fun `the on-screen Backspace and Enter keys delete and submit`(robot: FxRobot) {
        "CRANE".forEach { c -> robot.interact { view.keyboard.keyOf(c)!!.fire() } }
        robot.interact { view.keyboard.keyOf(KeyboardKey.Backspace)!!.fire() }
        assertEquals("CRAN", fx { model.input })

        robot.interact { view.keyboard.keyOf('E')!!.fire() }
        robot.interact { view.keyboard.keyOf(KeyboardKey.Enter)!!.fire() }

        assertEquals("Solved in 1 of 6 — Enter for the next word", fx { view.status.text })
    }

    @Test
    fun `Backspace and F2 reach the model and the status line`(robot: FxRobot) {
        typeKeys(robot, "ab")
        press(robot, KeyCode.BACK_SPACE)
        assertEquals("A", fx { model.input })

        press(robot, KeyCode.F2)

        assertEquals("Skipped: the word was CRANE", fx { view.status.text })
        assertEquals("", fx { model.input })
    }

    @Test
    fun `the Next Word button skips the round and returns the focus to the grid`(robot: FxRobot) {
        robot.interact { view.languageMenu.requestFocus() }

        robot.interact { view.nextWordButton.fire() }

        assertEquals("Skipped: the word was CRANE", fx { view.status.text })
        assertSame(view.grid, fx { stage.scene.focusOwner })
    }

    @Test
    fun `a rejected word keeps the letters of the row and shakes it`(robot: FxRobot) {
        typeKeys(robot, "zzzzz")

        // Read while the short shake still runs: on a slow runner it could be over by the next FX round trip.
        val shaking =
            fx {
                Event.fireEvent(
                    view.grid,
                    KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false),
                )
                view.grid.isShaking
            }

        assertTrue(shaking)
        assertEquals("Not in the word list", fx { view.status.text })
        assertEquals(listOf("Z", "Z", "Z", "Z", "Z"), fx { tiles(0).map { it.text } })
    }

    @Test
    fun `choosing Deutsch shows the german keyboard and a new round`(robot: FxRobot) {
        assertNull(fx { view.keyboard.keyOf('Ä') })
        robot.interact { view.languageMenu.requestFocus() }

        robot.interact {
            view.languageMenu.items
                .first { it.text == "Deutsch" }
                .fire()
        }

        assertNotNull(fx { view.keyboard.keyOf('Ä') })
        assertEquals("Deutsch", fx { view.languageMenu.text })
        assertEquals("Guess 1 of 6", fx { view.status.text })
        assertEquals(Language.DE, fx { model.language })
        assertSame(view.grid, fx { stage.scene.focusOwner })
    }

    @Test
    fun `typing with the Shortcut key is left to the menu accelerators`(robot: FxRobot) {
        val event = KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, true, false, false)
        robot.interact { Event.fireEvent(view.grid, event) }

        assertFalse(event.isConsumed)
    }

    @Test
    fun `focusing the tab content hands the focus to the grid`(robot: FxRobot) {
        robot.interact { view.languageMenu.requestFocus() }
        assertFalse(fx { view.grid.isFocused })

        robot.interact { view.node.requestFocus() }

        assertSame(view.grid, fx { stage.scene.focusOwner })
    }

    @Test
    fun `the tiles follow the size of the window`(robot: FxRobot) {
        val large = fx { tiles(0)[0].width }

        robot.interact {
            stage.width = 380.0
            stage.height = 520.0
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(fx { tiles(0)[0].width } < large)
    }

    @Test
    fun `a disposed view leaves the model to the view that took it over`(robot: FxRobot) {
        lateinit var second: WordGuessView
        robot.interact {
            second = WordGuessView(model)
            view.dispose()
            stage.scene.root = second.node as Parent
        }

        typeKeys(robot, "a")
        assertEquals("A", fx { (second.grid.children[0] as HBox).children.map { (it as Label).text }[0] })
        assertEquals("", fx { tiles(0)[0].text })

        robot.interact { second.dispose() }
        robot.interact { model.type('b') }
        assertEquals("A", fx { (second.grid.children[0] as HBox).children.map { (it as Label).text }.joinToString("") })
    }
}
