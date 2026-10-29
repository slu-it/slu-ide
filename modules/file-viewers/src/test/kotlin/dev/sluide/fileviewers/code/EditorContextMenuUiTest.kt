package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.CodeEditorExtensions
import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.WindowServices
import dev.sluide.fileviewers.languages.PythonFileViewer
import dev.sluide.fileviewers.press
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.PickResult
import javafx.scene.layout.StackPane
import javafx.scene.text.Font
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The context menu of a real [CodeView] ([EditorContextMenu]; which items can act is `EditorContextMenuTest`'s). Needs
 * a display: where a press hits the text, how the menu is requested and shown, and how keys reach the area are event
 * routing in a laid-out scene.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorContextMenuUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()
    private lateinit var view: CodeView

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 500.0, 300.0)
        stage.show()
    }

    @AfterEach
    fun dispose() {
        if (::view.isInitialized) WaitForAsyncUtils.asyncFx { view.dispose() }.get()
    }

    private fun open(
        robot: FxRobot,
        text: String = "alpha beta\ngamma delta\n",
        host: FileViewHost? = null,
    ): CodeView {
        val file = dir.resolve("main.py").apply { writeText(text) }
        view = WaitForAsyncUtils.asyncFx<CodeView> { PythonFileViewer().open(file) as CodeView }.get()
        robot.interact {
            container.children.setAll(view.node)
            view.host = host
            view.area.requestFocus()
        }
        return view
    }

    private val CodeView.menu: ContextMenu get() = checkNotNull(area.contextMenu)

    /** Requests the menu as a right-click at the top left of the area does after the press, or as a key does. */
    private fun requestMenu(
        view: CodeView,
        robot: FxRobot,
        keyboard: Boolean = false,
    ) {
        robot.interact {
            val screen = view.area.localToScreen(5.0, 5.0)
            Event.fireEvent(
                view.area,
                ContextMenuEvent(
                    ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    5.0,
                    5.0,
                    screen.x,
                    screen.y,
                    keyboard,
                    PickResult(view.area, 5.0, 5.0),
                ),
            )
        }
    }

    /** How a context menu is asked for with the mouse: right-click, or Ctrl+click (a popup trigger on macOS). */
    enum class Click(
        val button: MouseButton,
        val control: Boolean,
    ) {
        RIGHT(MouseButton.SECONDARY, false),
        CONTROL(MouseButton.PRIMARY, true),
    }

    /** Presses and releases [click] over the left edge of the character at [index] (the press is the popup trigger). */
    private fun click(
        view: CodeView,
        index: Int,
        click: Click,
        robot: FxRobot,
    ) {
        robot.interact {
            val area = view.area
            val bounds = area.getCharacterBoundsOnScreen(index, index + 1).orElseThrow()
            val scene = area.localToScene(area.screenToLocal(bounds.minX + 1, bounds.centerY))
            for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED)) {
                val pressed = type == MouseEvent.MOUSE_PRESSED
                Event.fireEvent(
                    area,
                    MouseEvent(
                        type,
                        scene.x,
                        scene.y,
                        bounds.minX + 1,
                        bounds.centerY,
                        click.button,
                        1,
                        false,
                        click.control,
                        false,
                        false,
                        pressed && click.button == MouseButton.PRIMARY,
                        false,
                        pressed && click.button == MouseButton.SECONDARY,
                        false,
                        pressed,
                        true,
                        null,
                    ),
                )
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Click::class)
    fun `a click for the menu outside the selection moves the caret there, inside it keeps the selection`(
        how: Click,
        robot: FxRobot,
    ) {
        val view = open(robot)
        robot.interact { view.area.selectRange(0, 5) }

        click(view, 2, how, robot)
        assertEquals(0 to 5, view.area.selection.let { it.start to it.end }, "inside the selection")

        click(view, 13, how, robot)
        assertEquals(13, view.area.caretPosition)
        assertEquals(0, view.area.selection.length)
    }

    @Test
    fun `a right-click in the gutter neither moves the caret nor shows the menu`(robot: FxRobot) {
        val view = open(robot)
        robot.interact { view.area.moveTo(3) }
        val number =
            robot
                .from(view.area)
                .lookup(".lineno")
                .queryAllAs(Label::class.java)
                .first { it.text.trim() == "2" }

        robot.interact {
            val center = number.localToScene(number.width / 2, number.height / 2)
            val screen = number.localToScreen(number.width / 2, number.height / 2)
            val pick = PickResult(number, center.x, center.y)
            Event.fireEvent(
                number,
                MouseEvent(
                    MouseEvent.MOUSE_PRESSED,
                    center.x,
                    center.y,
                    screen.x,
                    screen.y,
                    MouseButton.SECONDARY,
                    1,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    true,
                    true,
                    pick,
                ),
            )
            Event.fireEvent(
                number,
                ContextMenuEvent(
                    ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    center.x,
                    center.y,
                    screen.x,
                    screen.y,
                    false,
                    pick,
                ),
            )
        }

        assertEquals(3, view.area.caretPosition)
        assertFalse(view.menu.isShowing)
    }

    @Test
    fun `the menu shows the editing items with their keys and acts on the text`(robot: FxRobot) {
        val view = open(robot)

        requestMenu(view, robot)

        val menu = view.menu
        assertTrue(menu.isShowing)
        assertEquals(
            listOf("Undo", "Redo", null, "Cut", "Copy", "Paste", "Select All"),
            menu.items.map { if (it is SeparatorMenuItem) null else it.text },
        )
        val copy = menu.items.single { it.text == "Copy" }
        assertTrue(copy.isDisable, "nothing selected")
        assertTrue(menu.items.single { it.text == "Undo" }.isDisable, "nothing to undo in a file just opened")
        assertEquals(
            KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
            menu.items.single { it.text == "Redo" }.accelerator,
        )

        robot.interact { menu.hide() }
        // As the editor area sets the editor font; the menu still reads in the interface font.
        robot.interact { container.style = "-fx-font-family: \"Monospaced\"; -fx-font-style: italic;" }
        requestMenu(view, robot)
        val label = robot.lookup(".code-editor-context-menu .label").queryAs(Label::class.java)
        assertEquals(Font.getDefault().family, label.font.family)
        assertEquals("Regular", label.font.style)

        robot.interact { menu.items.single { it.text == "Select All" }.fire() }
        assertEquals(view.area.length, view.area.selection.length)
        robot.interact { menu.hide() }

        requestMenu(view, robot)
        assertFalse(copy.isDisable, "rebuilt with the selection")
    }

    @ParameterizedTest
    @ValueSource(strings = ["Shift+F10", "a request by keyboard"])
    fun `the menu opened by keyboard shows below the caret`(
        how: String,
        robot: FxRobot,
    ) {
        val view = open(robot)
        robot.interact { view.area.moveTo(1, 3) }

        if (how == "Shift+F10") robot.press(view.area, KeyCode.F10, shift = true) else requestMenu(view, robot, true)

        val menu = view.menu
        assertTrue(menu.isShowing)
        val caret = view.area.caretBounds.orElseThrow()
        assertEquals(caret.minX to caret.maxY, menu.anchorX to menu.anchorY)
    }

    @Test
    fun `items of extensions come after a separator, for the caret, without those of a failing one`(robot: FxRobot) {
        val targets = mutableListOf<EditorMenuTarget>()
        val extensions =
            CodeEditorExtensions().apply {
                add { editor ->
                    editor.addContextMenuItems { error("broken") }
                    editor.addContextMenuItems { target ->
                        targets += target
                        listOf(MenuItem("Extension Item"))
                    }
                    null
                }
            }
        val view = open(robot, host = host(extensions))
        robot.interact { view.area.moveTo(1, 3) }

        requestMenu(view, robot)

        assertEquals(listOf(EditorMenuTarget(1, 3)), targets)
        val items = view.menu.items
        assertEquals("Extension Item", items.last().text)
        assertIs<SeparatorMenuItem>(items[items.size - 2])
    }

    @Test
    fun `Shortcut+Z after the menu was shown undoes one step`(robot: FxRobot) {
        val view = open(robot, text = "")
        robot.interact {
            view.area.insertText(0, "a")
            view.area.undoManager.preventMerge()
            view.area.insertText(1, "b")
        }
        requestMenu(view, robot)
        robot.interact { view.menu.hide() }

        robot.press(view.area, KeyCode.Z, shortcut = true)

        assertEquals("a", view.area.text)
    }

    /** A host of a window whose services hold [extensions]. */
    private fun host(extensions: CodeEditorExtensions) =
        object : FileViewHost {
            private val services = WindowServices().apply { register(CodeEditorExtensions::class.java, extensions) }

            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
        }
}
