package dev.sluide.translation.ui

import dev.sluide.fileviewers.ViewActivity
import dev.sluide.localmodels.InstallState
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.translation.TranslationPreferences
import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality
import dev.sluide.translation.session.FakeDetector
import dev.sluide.translation.session.FakeEngine
import dev.sluide.translation.session.FakeInstalls
import dev.sluide.translation.session.ManualScheduler
import dev.sluide.translation.session.SourceChoice
import dev.sluide.translation.session.TranslateModel
import dev.sluide.translation.session.english
import dev.sluide.translation.session.german
import dev.sluide.translation.session.swedish
import javafx.beans.property.SimpleStringProperty
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.input.Clipboard
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.stage.Stage
import org.fxmisc.richtext.GenericStyledArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wiring of the Translate view to its model: typing, the pickers, quality, Markdown, the notice, Copy and Open as
 * Scratch, and the read-only translation. The model runs in place with fake engine, detector and installs; its rules
 * are in `TranslateModelTest`. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TranslateViewUiTest {
    @TempDir
    lateinit var dir: Path

    private val engine = FakeEngine()
    private val installs = FakeInstalls()
    private val scheduler = ManualScheduler()
    private val scratches = mutableListOf<Pair<String, String>>()
    private lateinit var model: TranslateModel
    private lateinit var view: TranslateView

    @Start
    fun start(stage: Stage) {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = TranslationPreferences(store, java.time.Duration.ZERO) { it.run() }
        model =
            TranslateModel(engine, FakeDetector(german), installs, preferences, Runnable::run, scheduler, { it.run() })
        view = TranslateView(model, SimpleStringProperty("")) { text, extension -> scratches += text to extension }
        stage.scene = Scene(view.node as BorderPane, 900.0, 400.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(Callable { block() }).get()

    private fun <T : javafx.scene.Node> node(
        id: String,
        type: Class<T>,
    ): T = fx { type.cast(checkNotNull(FxRobot().lookup("#$id").query<javafx.scene.Node>()) { "no #$id" }) }

    @Suppress("UNCHECKED_CAST")
    private fun areas(): List<GenericStyledArea<Any?, Any?, Collection<String>>> =
        fx {
            view.node.lookupAll(".styled-text-area").map { it as GenericStyledArea<Any?, Any?, Collection<String>> }
        }

    private val left get() = areas()[0]
    private val right get() = areas()[1]

    @Test
    fun `typing reaches the model, which translates after its delay, and the tab shows it runs`(robot: FxRobot) {
        robot.interact { model.chooseTarget(english) }

        robot.interact { left.replaceText("Hallo Welt.") }
        assertEquals("Hallo Welt.", fx { model.text })
        assertEquals(ViewActivity.RUNNING, fx { view.activity.value })
        robot.interact { scheduler.fire() }

        assertEquals("HALLO WELT.", fx { right.text })
        assertEquals("Translated", fx { node("translate-status", Label::class.java).text })
        assertEquals(ViewActivity.NONE, fx { view.activity.value })
    }

    @Test
    fun `the target picker filters by what is typed and picks with Enter`(robot: FxRobot) {
        robot.interact { node("translate-target", Button::class.java).fire() }
        val search = node("translate-target-search", TextField::class.java)

        robot.interact { search.text = "swe" }
        assertEquals(
            listOf("Swedish"),
            fx {
                node(
                    "translate-target-list",
                    javafx.scene.control.ListView::class.java,
                ).items.map { (it as Choice<*>).label }
            },
        )
        robot.interact {
            Event.fireEvent(search, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
        }

        assertEquals(swedish, fx { model.target })
        assertEquals("Swedish", fx { node("translate-target", Button::class.java).text })
    }

    @Test
    fun `the source picker offers Detect first and picks a language`(robot: FxRobot) {
        robot.interact { model.chooseTarget(english) }
        robot.interact { node("translate-source", Button::class.java).fire() }
        val list = node("translate-source-list", javafx.scene.control.ListView::class.java)
        assertEquals("Detect", fx { (list.items.first() as Choice<*>).label })

        val search = node("translate-source-search", TextField::class.java)
        robot.interact { search.text = "GERM" }
        robot.interact {
            Event.fireEvent(search, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
        }

        assertEquals(SourceChoice.Picked(german), fx { model.source })
    }

    @Test
    fun `quality and Markdown go to the model`(robot: FxRobot) {
        robot.interact {
            @Suppress("UNCHECKED_CAST")
            (node("translate-quality", ComboBox::class.java) as ComboBox<Quality>).value =
                Quality.HIGH
        }
        robot.interact { node("translate-markdown", ToggleButton::class.java).fire() }

        assertEquals(Quality.HIGH, fx { model.quality })
        assertTrue(fx { model.markdown })
    }

    @Test
    fun `the translation follows the output, its caret survives a status-only change, and it is read-only`(
        robot: FxRobot,
    ) {
        robot.interact { model.chooseTarget(english) }
        robot.interact { model.open("Hallo Welt.", markdown = false) }
        assertEquals("Hallo Welt.", fx { left.text })
        assertEquals("HALLO WELT.", fx { right.text })

        robot.interact { right.selectRange(2, 5) }
        robot.interact { model.onChange() }
        assertEquals("LLO", fx { right.selectedText })
        assertFalse(fx { right.isEditable })

        robot.interact { model.open("Guten Tag.", markdown = false) }
        assertEquals("GUTEN TAG.", fx { right.text })
    }

    @Test
    fun `the notice offers Download, then shows progress and Cancel`(robot: FxRobot) {
        val missing = PairModel("de", "en", Quality.MEDIUM)
        engine.notInstalled += missing
        robot.interact { model.chooseTarget(english) }
        robot.interact { model.open("Hallo Welt.", markdown = false) }
        assertEquals("Download (0 MB)", fx { node("translate-download", Button::class.java).text })

        robot.interact { node("translate-download", Button::class.java).fire() }
        assertEquals(listOf(missing), installs.downloads.single())

        robot.interact { installs.set(missing, InstallState.Downloading(0.5)) }
        assertEquals(0.5, fx { node("translate-download-progress", ProgressBar::class.java).progress })
        robot.interact { node("translate-download-cancel", Button::class.java).fire() }
        assertEquals(listOf(missing), installs.cancels.single())
    }

    @Test
    fun `Copy and Open as Scratch take the whole translation`(robot: FxRobot) {
        robot.interact { model.chooseTarget(english) }
        robot.interact { model.open("Hallo Welt.", markdown = false) }

        robot.interact { node("translate-copy", Button::class.java).fire() }
        assertEquals("HALLO WELT.", fx { Clipboard.getSystemClipboard().string })
        robot.interact { node("translate-scratch", Button::class.java).fire() }
        robot.interact { node("translate-markdown", ToggleButton::class.java).fire() }
        robot.interact { node("translate-scratch", Button::class.java).fire() }

        assertEquals(listOf("HALLO WELT." to "txt", "HALLO WELT." to "md"), scratches)
    }

    private fun press(
        target: javafx.scene.Node,
        code: KeyCode,
    ) = Event.fireEvent(target, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))

    @Test
    fun `Up and Down move the selection and Enter picks it, Escape closes without picking`(robot: FxRobot) {
        robot.interact { node("translate-target", Button::class.java).fire() }
        val search = node("translate-target-search", TextField::class.java)
        val list = node("translate-target-list", javafx.scene.control.ListView::class.java)

        robot.interact { press(search, KeyCode.DOWN) }
        assertEquals(1, fx { list.selectionModel.selectedIndex })
        robot.interact { press(search, KeyCode.DOWN) }
        robot.interact { press(search, KeyCode.UP) }
        assertEquals(1, fx { list.selectionModel.selectedIndex })
        robot.interact { press(search, KeyCode.ESCAPE) }
        assertFalse(fx { search.scene.window.isShowing })
        assertEquals(null, fx { model.target })

        robot.interact { node("translate-target", Button::class.java).fire() }
        robot.interact { press(search, KeyCode.DOWN) }
        robot.interact { press(search, KeyCode.ENTER) }
        assertEquals(english, fx { model.target })
    }

    @Test
    fun `the picker opens on the current choice, so Enter keeps it`(robot: FxRobot) {
        robot.interact { model.chooseTarget(swedish) }
        robot.interact { node("translate-target", Button::class.java).fire() }

        robot.interact { press(node("translate-target-search", TextField::class.java), KeyCode.ENTER) }

        assertEquals(swedish, fx { model.target })
    }

    @Test
    fun `a disposed view stops following the model, a newer one on the same model keeps following`(robot: FxRobot) {
        val stage = fx { Stage() }
        try {
            val second =
                fx {
                    TranslateView(model, SimpleStringProperty("")) { _, _ -> }.also {
                        // The text areas exist once the view is in a shown scene.
                        stage.scene = Scene(it.node as BorderPane, 600.0, 300.0)
                        stage.show()
                    }
                }
            WaitForAsyncUtils.waitForFxEvents()
            robot.interact { view.dispose() }
            robot.interact { model.chooseTarget(english) }
            robot.interact { model.open("Hallo Welt.", markdown = false) }

            val secondOutput =
                fx {
                    (second.node.lookupAll(".styled-text-area")).map { it as GenericStyledArea<*, *, *> }[1].text
                }
            assertEquals("HALLO WELT.", secondOutput)
        } finally {
            robot.interact { stage.close() }
        }
        assertEquals("", fx { right.text })
    }

    @Test
    fun `disposing the tab disposes its view and calls onClose`(robot: FxRobot) {
        var closed = 0
        val tab = TranslateTab("Translate 7", view) { closed++ }
        assertEquals("Translate 7", tab.title.value)

        robot.interact { tab.dispose() }
        robot.interact { model.chooseTarget(english) }
        robot.interact { model.open("Hallo Welt.", markdown = false) }

        assertEquals(1, closed)
        assertEquals("", fx { right.text }, "the disposed view no longer follows the model")
    }

    @Test
    fun `a click picks only on an entry, not on the list itself`(robot: FxRobot) {
        robot.interact { node("translate-target", Button::class.java).fire() }
        val list = node("translate-target-list", javafx.scene.control.ListView::class.java)

        fun click(target: javafx.scene.Node) =
            Event.fireEvent(
                target,
                MouseEvent(
                    MouseEvent.MOUSE_CLICKED,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    MouseButton.PRIMARY,
                    1,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    true,
                    false,
                    false,
                    null,
                ),
            )

        robot.interact { click(list) }
        assertEquals(null, fx { model.target })

        val cell =
            fx {
                list.lookupAll(".list-cell").map { it as javafx.scene.control.ListCell<*> }.first {
                    it.text ==
                        "English"
                }
            }
        robot.interact { click(cell) }
        assertEquals(english, fx { model.target })
    }
}
