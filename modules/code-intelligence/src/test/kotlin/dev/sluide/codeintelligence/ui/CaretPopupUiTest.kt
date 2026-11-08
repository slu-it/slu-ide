package dev.sluide.codeintelligence.ui

import dev.sluide.codeintelligence.EditorUiFixture
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.textediting.TextPosition
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.input.KeyCode
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The caret popup over a real code view: placed below its anchor, the keys taken through the editor's key bindings
 * before the editor's own (Enter takes the item instead of a new line), items replaced while it shows, the
 * placeholder. The list model's rules are `PopupListModelTest`'s. Needs a display: key routing, screen bounds and the
 * popup window exist only in a shown scene.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class CaretPopupUiTest : EditorUiFixture() {
    @TempDir
    lateinit var dir: Path

    private val chosen = mutableListOf<String>()
    private lateinit var editor: ExtensibleCodeEditor
    private lateinit var popup: CaretPopup<String>

    @Start
    fun start(stage: Stage) = startStage(stage)

    @AfterEach
    fun close() {
        fx { popup.close() }
        closeView()
    }

    private fun setUp(
        robot: FxRobot,
        text: String = "import os\nos.\n",
    ) {
        editor = open(robot, dir.resolve("a.py"), text, emptyList())
        popup = fx { CaretPopup(editor, { PopupCell(it, secondary = "fn") }, { chosen += it }) }
    }

    private fun showPopup(robot: FxRobot) {
        robot.interact {
            popup.setItems(listOf("path", "sep", "getcwd"))
            assertTrue(popup.show(13))
        }
    }

    private fun findBarOpen() = fx { view?.node?.lookup(".find-bar") != null }

    @Test
    fun `the popup hides when the window moves, the editor scrolls or the text area loses the focus`(robot: FxRobot) {
        setUp(robot, "import os\nos.\n" + (1..300).joinToString("\n") { "x$it = $it" })
        showPopup(robot)
        robot.interact { stage.x += 10 }
        assertFalse(fx { popup.isShowing }, "the window moved")

        showPopup(robot)
        robot.interact { editor.reveal(editor.offsetOf(TextPosition(250, 0)), editor.offsetOf(TextPosition(250, 0))) }
        assertFalse(fx { popup.isShowing }, "scrolled")
        robot.interact { editor.reveal(0, 0) }

        val area = area()
        robot.interact { area.requestFocus() }
        assertTrue(fx { area.isFocused }, "the test needs the focus in the area")
        showPopup(robot)
        // Shortcut+F moves the focus to the find field.
        press(robot, KeyCode.F, shortcut = true)
        assertTrue(findBarOpen())
        assertFalse(fx { popup.isShowing }, "the area lost the focus")
    }

    @Test
    fun `Escape closes the popup and leaves an open find bar open`(robot: FxRobot) {
        setUp(robot)
        press(robot, KeyCode.F, shortcut = true)
        assertTrue(findBarOpen())
        showPopup(robot)

        press(robot, KeyCode.ESCAPE)
        assertFalse(fx { popup.isShowing })
        assertTrue(findBarOpen(), "the popup took Escape")
        press(robot, KeyCode.ESCAPE)
        assertFalse(findBarOpen(), "the next Escape is the editor's")
    }

    private fun list() =
        fx {
            popups("caret-popup")
                .single()
                .content
                .single()
                .lookup(".caret-popup-list") as ListView<*>
        }

    @Test
    fun `the popup shows below its anchor, and the keys choose and take an item before the editor's own`(
        robot: FxRobot,
    ) {
        setUp(robot)
        robot.interact {
            popup.setItems(listOf("path", "sep", "getcwd"))
            assertTrue(popup.show(13))
        }
        val window = popups("caret-popup").single()
        val anchor = bounds(editor, 13)
        assertTrue(fx { window.y } >= anchor.maxY - 1, "below the caret: ${fx { window.y }} vs ${anchor.maxY}")
        assertEquals(anchor.minX, fx { window.x }, 1.0)

        press(robot, KeyCode.DOWN)
        press(robot, KeyCode.DOWN)
        assertEquals(2, fx { list().selectionModel.selectedIndex })
        press(robot, KeyCode.UP)
        assertEquals("sep", fx { popup.selected })

        press(robot, KeyCode.ENTER)
        assertEquals(listOf("sep"), chosen)
        assertFalse(fx { popup.isShowing })
        assertEquals("import os\nos.\n", fx { editor.text }, "Enter took the item, no new line")

        press(robot, KeyCode.ENTER)
        assertTrue(fx { editor.text }.length > 14, "hidden, the popup gave Enter back to the editor")
    }

    @Test
    fun `nothing in the popup takes the focus, so Enter and Space stay the editor's`(robot: FxRobot) {
        editor = open(robot, dir.resolve("a.py"), "import os\nos.\n", emptyList())
        val button = fx { Button("Docs") }
        popup = fx { CaretPopup(editor, { PopupCell(it) }, { chosen += it }, detail = { button }) }
        showPopup(robot)
        assertFalse(fx { button.isFocusTraversable }, "a button in the detail pane")
        assertFalse(fx { list().isFocusTraversable }, "the list")
    }

    @Test
    fun `items change while it shows, keeping the selected one, and an empty list shows the placeholder`(
        robot: FxRobot,
    ) {
        setUp(robot)
        robot.interact {
            popup.setItems(listOf("path", "sep", "getcwd"))
            popup.show(13)
        }
        // Typing at the anchor (completion filters while typing) keeps the popup.
        robot.interact { editor.applyEdits(listOf(OffsetEdit(13, 13, "g"))) }
        assertTrue(fx { popup.isShowing }, "typing keeps it")
        press(robot, KeyCode.END)
        robot.interact { popup.setItems(listOf("getcwd", "getenv")) }
        assertEquals("getcwd", fx { popup.selected })
        assertEquals(listOf("getcwd", "getenv"), fx { list().items.toList() })

        robot.interact {
            popup.placeholder = "Searching…"
            popup.setItems(emptyList())
        }
        assertEquals("Searching…", fx { (list().placeholder as Label).text })
        press(robot, KeyCode.ENTER)
        assertTrue(chosen.isEmpty(), "nothing to take: Enter goes to the editor")
        press(robot, KeyCode.ESCAPE)
        assertFalse(fx { popup.isShowing })
    }
}
