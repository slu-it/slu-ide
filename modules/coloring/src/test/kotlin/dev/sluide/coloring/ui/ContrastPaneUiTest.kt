package dev.sluide.coloring.ui

import dev.sluide.coloring.analysis.contrastRatio
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.ui.contrast.contrastView
import javafx.css.PseudoClass
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ButtonBase
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
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
import kotlin.test.assertNotNull
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

    private fun toggle(text: String) =
        root.lookupAll(".toggle-button").filterIsInstance<ToggleButton>().first {
            it.text ==
                text
        }

    private fun field() = root.lookup("#contrast-second") as TextField

    @Test
    fun `the ratio follows the current color`() {
        val text =
            fx {
                state.current.set(black)
                state.second.set(white)
                ratio()
            }
        assertEquals("21.00 : 1", text)
    }

    @Test
    fun `swap exchanges the colors exactly`() {
        val precise = CssColor.srgb(0.123456, 0.5, 0.7)
        fx {
            state.current.set(precise)
            state.second.set(white)
            button("Swap").fire()
        }
        val result = fx { state.current.get() to state.second.get() }
        assertEquals(white, result.first)
        assertEquals(precise, result.second)
    }

    @Test
    fun `use applies the suggestion to the text color`() {
        val result =
            fx {
                state.current.set(grey)
                state.second.set(white)
                button("Use").fire()
                state.current.get() to state.second.get()
            }
        assertEquals(white, result.second)
        assertTrue(contrastRatio(result.first, white) >= 4.5)
    }

    @Test
    fun `use in the background role sets the second color exactly to the suggestion`() {
        val suggestion = assertNotNull(contrastView(white, grey, currentIsText = false).suggestion?.color)
        val result =
            fx {
                state.currentIsText.set(false)
                state.current.set(white)
                state.second.set(grey)
                button("Use").fire()
                state.current.get() to state.second.get()
            }
        assertEquals(white, result.first)
        assertEquals(suggestion, result.second)
        assertTrue(contrastRatio(result.second, white) >= 4.5)
    }

    @Test
    fun `the role toggle sets currentIsText`() {
        val result =
            fx {
                toggle("Background").fire()
                val afterBackground = state.currentIsText.get()
                toggle("Text").fire()
                afterBackground to state.currentIsText.get()
            }
        assertEquals(false, result.first)
        assertEquals(true, result.second)
    }

    @Test
    fun `typing in the field sets the second color and marks invalid input`() {
        val invalid = PseudoClass.getPseudoClass("invalid")
        val result =
            fx {
                field().text = "#ff0000"
                val typed = state.second.get()
                field().text = "zzz"
                Triple(typed, state.second.get(), field().pseudoClassStates.contains(invalid))
            }
        assertEquals(CssColor.fromRgb8(255, 0, 0), result.first)
        assertEquals(CssColor.fromRgb8(255, 0, 0), result.second)
        assertTrue(result.third)
    }

    @Test
    fun `the White and Black buttons set the second color`() {
        val result =
            fx {
                button("Black").fire()
                val afterBlack = state.second.get()
                button("White").fire()
                afterBlack to state.second.get()
            }
        assertEquals(black, result.first)
        assertEquals(white, result.second)
    }

    @Test
    fun `a disposed pane no longer follows the state`() {
        fx {
            state.current.set(black)
            state.second.set(white)
        }
        val before = fx { ratio() }
        val after =
            fx {
                pane.dispose()
                state.current.set(white)
                ratio()
            }
        assertEquals(before, after)
    }
}
