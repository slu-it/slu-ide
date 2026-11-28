package dev.sluide.codeintelligence.ui

import dev.sluide.codeintelligence.EditorUiFixture
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.FilePreview
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.textediting.TextPosition
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Orientation
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.ScrollBar
import javafx.scene.input.KeyCode
import javafx.scene.layout.StackPane
import javafx.stage.Screen
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
    fun `Enter on an item that cannot be taken keeps the popup and the text`(robot: FxRobot) {
        setUp(robot)
        robot.interact { popup.close() }
        popup = fx { CaretPopup(editor, { PopupCell(it) }, { chosen += it }, choosable = { it != "sep" }) }
        showPopup(robot)
        press(robot, KeyCode.DOWN)

        press(robot, KeyCode.ENTER)
        assertEquals(emptyList(), chosen)
        assertTrue(fx { popup.isShowing })
        assertEquals("import os\nos.\n", fx { editor.text }, "the popup kept Enter")
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
    fun `a row wider than the list ends its secondary text in an ellipsis instead of scrolling sideways`(
        robot: FxRobot,
    ) {
        editor = open(robot, dir.resolve("a.py"), "import os\nos.\n", emptyList())
        val long = "rich/" + "very_long_folder_name/".repeat(12) + "file.py · data[\"k\"].greet()"
        popup = fx { CaretPopup(editor, { PopupCell(it, secondary = long) }, { chosen += it }) }
        robot.interact {
            popup.setItems(listOf("probe_thoreau.py:40"))
            assertTrue(popup.show(13))
        }
        val list = list()
        val bars = fx { list.lookupAll(".scroll-bar").filterIsInstance<ScrollBar>() }
        assertTrue(
            fx { bars.none { it.orientation == Orientation.HORIZONTAL && it.isVisible } },
            "no sideways scroll bar",
        )
        val secondary = fx { list.lookup(".caret-popup-secondary") as Label }
        assertTrue(fx { secondary.width < secondary.prefWidth(-1.0) }, "the secondary text gave way")
        assertTrue(fx { (list.lookup(".caret-popup-label") as Label).width > 0.0 }, "the label shows")
    }

    @Test
    fun `items change while it shows, keeping the one a key chose, and an empty list shows the placeholder`(
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
        robot.interact { popup.setItems(listOf("getenv", "getcwd")) }
        assertEquals("getcwd", fx { popup.selected }, "the key's choice stays, though it is not the first now")
        assertEquals(1, fx { list().selectionModel.selectedIndex })
        assertEquals(listOf("getenv", "getcwd"), fx { list().items.toList() })

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

    /** A popup with a footer of [height] under its list; the footer. */
    private fun withFooter(
        robot: FxRobot,
        height: Double = 60.0,
    ): StackPane {
        editor = open(robot, dir.resolve("a.py"), "import os\nos.\n", emptyList())
        val footer = fx { StackPane().apply { setPrefSize(100.0, height) } }
        popup = fx { CaretPopup(editor, { PopupCell(it) }, { chosen += it }, footer = footer) }
        return footer
    }

    @Test
    fun `the footer shows under the list only while there are items`(robot: FxRobot) {
        val footer = withFooter(robot)
        robot.interact {
            popup.setItems(emptyList())
            popup.show(13)
        }
        assertFalse(fx { footer.isVisible || footer.isManaged }, "only the placeholder shows")
        val withoutFooter = fx { popups("caret-popup").single().height }

        robot.interact { popup.setItems(listOf("path", "sep")) }
        assertTrue(fx { footer.isVisible && footer.isManaged })
        val list = list()
        val listBottom = fx { list.localToScene(list.boundsInLocal).maxY }
        assertTrue(fx { footer.localToScene(footer.boundsInLocal).minY } >= listBottom - 1, "under the list")
        assertTrue(fx { popups("caret-popup").single().height } >= withoutFooter + 60.0, "the window grew")

        robot.interact { popup.setItems(emptyList()) }
        assertFalse(fx { footer.isVisible || footer.isManaged }, "hidden again")
    }

    @Test
    fun `onSelected tells a key's selection, not the same position after setItems keeping the index`(robot: FxRobot) {
        withFooter(robot)
        val told = mutableListOf<String?>()
        robot.interact {
            popup.onSelected = { told += it }
            popup.setItems(listOf("path", "sep", "getcwd"))
        }
        assertEquals(emptyList(), told, "nothing is told before the popup shows")
        robot.interact { popup.show(13) }
        assertEquals(listOf<String?>("path"), told, "the first item once the popup shows")
        press(robot, KeyCode.DOWN)
        assertEquals(listOf<String?>("path", "sep"), told)

        robot.interact { popup.setItems(listOf("PATH", "SEP", "GETCWD"), keepIndex = true) }
        assertEquals("SEP", fx { popup.selected })
        assertEquals(listOf<String?>("path", "sep"), told, "same position: nothing to tell")
        robot.interact { popup.setItems(listOf("PATH", "SEP", "GETENV")) }
        assertEquals("SEP", fx { popup.selected })
        assertEquals(listOf<String?>("path", "sep"), told, "the same item stays selected: nothing to tell")
        robot.interact { popup.setItems(listOf("a", "b"), keepIndex = false) }
        assertEquals(listOf<String?>("path", "sep", "a"), told, "other items select the first")
        assertEquals("a", fx { popup.selected })
        robot.interact { popup.setItems(listOf("x", "y"), keepIndex = false) }
        assertEquals(listOf<String?>("path", "sep", "a", "x"), told, "another item at the same position")
    }

    @Test
    fun `a popup that hid tells its selection again when it shows`(robot: FxRobot) {
        withFooter(robot)
        val told = mutableListOf<String?>()
        robot.interact {
            popup.onSelected = { told += it }
            popup.setItems(listOf("path", "sep"))
            popup.show(13)
        }
        robot.interact { popup.setItems(listOf("path", "sep"), keepIndex = true) }
        assertEquals(listOf<String?>("path"), told)
        press(robot, KeyCode.DOWN)
        assertEquals("sep", fx { popup.selected })

        robot.interact { popup.hide() }
        robot.interact {
            popup.setItems(listOf("path", "sep"))
            popup.show(13)
        }
        assertEquals("path", fx { popup.selected }, "the key's choice does not outlive the showing")
        assertEquals(listOf<String?>("path", "sep", "path"), told, "told again by item")
        robot.interact { popup.hide() }
        robot.interact { popup.show(13) }
        assertEquals(4, told.size)
    }

    @Test
    fun `the footer is laid out at its size when the selection is told`(robot: FxRobot) {
        val footer = withFooter(robot, height = 60.0)
        val heights = mutableListOf<Double>()
        robot.interact {
            popup.onSelected = { heights += footer.height }
            popup.setItems(emptyList())
            popup.show(13)
        }
        assertEquals(emptyList(), heights)
        robot.interact { popup.setItems(listOf("path", "sep")) }
        assertEquals(listOf(60.0), heights, "laid out before the selection is told, as the first one after the show")

        robot.interact { popup.hide() }
        robot.interact { popup.show(13) }
        assertEquals(listOf(60.0, 60.0), heights, "also for the first selection after a new show")
    }

    @Test
    fun `a click in the list tells the selection`(robot: FxRobot) {
        withFooter(robot)
        val told = mutableListOf<String?>()
        robot.interact {
            popup.onSelected = { told += it }
            popup.setItems(listOf("path", "sep", "getcwd"))
            popup.show(13)
        }
        robot.interact { list().selectionModel.select(2) }
        assertEquals(listOf<String?>("path", "getcwd"), told)
    }

    @Test
    fun `a popup that grows when its footer appears moves above the anchor if it no longer fits below`(
        robot: FxRobot,
    ) {
        withFooter(robot, height = 232.0)
        val screen = fx { Screen.getPrimary().visualBounds }
        // The caret near the screen's bottom edge: a list fits below, a list with the footer does not.
        robot.interact { stage.y = screen.maxY - 260.0 }
        robot.interact {
            popup.setItems(emptyList())
            popup.show(13)
        }
        val anchor = bounds(editor, 13)
        val window = popups("caret-popup").single()
        assertTrue(fx { window.y } >= anchor.maxY - 1, "the placeholder fits below")

        robot.interact { popup.setItems(listOf("path", "sep", "getcwd")) }
        val grown = popups("caret-popup").single()
        assertTrue(
            fx { grown.y + grown.height } <= anchor.minY + 1,
            "above the caret: ${fx { grown.y + grown.height }} vs ${anchor.minY}",
        )
    }

    @Test
    fun `after the footer's file was swapped a key on the editor still moves the list, and the preview takes none`(
        robot: FxRobot,
    ) {
        editor = open(robot, dir.resolve("a.py"), "import os\nos.\n", emptyList())
        val preview =
            fx {
                FilePreview({ _, _ -> null }, { _, done -> done("one\ntwo\n") }, SimpleStringProperty(""))
                    .apply { node.setPrefSize(300.0, 100.0) }
            }
        popup = fx { CaretPopup(editor, { PopupCell(it) }, { chosen += it }, footer = preview.node) }
        robot.interact { area().requestFocus() }
        showPopup(robot)
        robot.interact { preview.show(dir.resolve("a.py")) { null } }
        robot.interact { preview.show(dir.resolve("b.py")) { null } }

        press(robot, KeyCode.DOWN)
        assertEquals("sep", fx { popup.selected }, "the key went through the editor's binding to the list")
        assertEquals(null, fx { popups("caret-popup").single().scene.focusOwner }, "the preview took no focus")
        assertTrue(fx { area().isFocused }, "the editor keeps it")
    }
}
