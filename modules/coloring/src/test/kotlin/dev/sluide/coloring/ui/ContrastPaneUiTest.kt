package dev.sluide.coloring.ui

import dev.sluide.coloring.analysis.contrastRatio
import dev.sluide.coloring.color.CssColor
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ButtonBase
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The wiring of the Contrast pane to the window's state; the decisions are in ContrastModelTest. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ContrastPaneUiTest {
    private val state = ColoringState()
    private val black = CssColor.srgb(0.0, 0.0, 0.0)
    private val white = CssColor.srgb(1.0, 1.0, 1.0)
    private val grey = CssColor.fromRgb8(150, 150, 150)
    private lateinit var pane: ContrastPane
    private lateinit var root: Parent

    @Start
    fun start(stage: Stage) {
        pane = ContrastPane(state)
        root = pane.node as Parent
        stage.scene = Scene(root, 500.0, 600.0)
        stage.show()
    }

    /** Runs [block] on the FX thread, waits for it and returns its value. */
    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(Callable { block() }).get(10, TimeUnit.SECONDS)

    private fun ratio() = (root.lookup("#contrast-ratio") as Label).text

    private fun button(text: String) =
        root.lookupAll(".button").filterIsInstance<ButtonBase>().first { it.text == text }

    private fun field() = root.lookup("#contrast-second") as TextField

    @Test
    fun `the ratio follows the text color`() {
        val text =
            fx {
                state.contrastText.color.set(black)
                state.contrastBackground.color.set(white)
                ratio()
            }
        assertEquals("21.00 : 1", text)
    }

    @Test
    fun `swap exchanges the colors exactly`() {
        val precise = CssColor.srgb(0.123456, 0.5, 0.7)
        fx {
            state.contrastText.color.set(precise)
            state.contrastBackground.color.set(white)
            button("Swap").fire()
        }
        val result = fx { state.contrastText.color.get() to state.contrastBackground.color.get() }
        assertEquals(white, result.first)
        assertEquals(precise, result.second)
    }

    @Test
    fun `use applies the suggestion to the text color`() {
        val result =
            fx {
                state.contrastText.color.set(grey)
                state.contrastBackground.color.set(white)
                button("Use").fire()
                state.contrastText.color.get() to state.contrastBackground.color.get()
            }
        assertEquals(white, result.second)
        assertTrue(contrastRatio(result.first, white) >= 4.5)
    }

    @Test
    fun `typing in the background field sets the background`() {
        val result =
            fx {
                field().text = "#ff0000"
                state.contrastBackground.color.get()
            }
        assertEquals(CssColor.fromRgb8(255, 0, 0), result)
    }

    @Test
    fun `the White and Black buttons set the background`() {
        val result =
            fx {
                button("Black").fire()
                val afterBlack = state.contrastBackground.color.get()
                button("White").fire()
                afterBlack to state.contrastBackground.color.get()
            }
        assertEquals(black, result.first)
        assertEquals(white, result.second)
    }

    @Test
    fun `a disposed pane no longer follows the state`() {
        fx {
            state.contrastText.color.set(black)
            state.contrastBackground.color.set(white)
        }
        val before = fx { ratio() }
        val after =
            fx {
                pane.dispose()
                state.contrastText.color.set(white)
                ratio()
            }
        assertEquals(before, after)
    }
}
