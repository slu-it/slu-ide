package dev.sluide.editor

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.PythonFileViewer
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.util.WaitForAsyncUtils
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The real editor area in a stage, shared by the `EditorArea*UiTest` classes. Needs a display, see the `uiTest` Gradle
 * task.
 */
abstract class EditorAreaUiFixture {
    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var settingsDir: Path

    internal lateinit var stage: Stage
    internal lateinit var editor: EditorArea
    internal lateinit var fontSettings: FontSettings
    internal val watcher = FileWatcher(Platform::runLater, 50.milliseconds)

    @AfterEach
    fun closeWatcher() = watcher.close()

    internal fun startView(stage: Stage) {
        this.stage = stage
        val fallback = DefaultTextFileViewer()
        fontSettings =
            FontSettings(SettingsStore(settingsDir.resolve("settings.properties")), FontSettings.systemDefaults())
        editor = EditorArea(FileViewers(listOf(fallback, PythonFileViewer()), fallback), watcher, fontSettings)
        stage.scene = Scene(editor, 800.0, 400.0)
        stage.show()
    }

    internal fun textFile(
        name: String,
        content: String = "content of $name",
    ) = dir.resolve(name).apply { writeText(content) }

    internal fun open(
        robot: FxRobot,
        vararg files: Path,
    ): List<Boolean> {
        val results = mutableListOf<Boolean>()
        robot.interact { files.forEach { results += editor.open(it) } }
        WaitForAsyncUtils.waitForFxEvents()
        return results
    }

    internal fun tabTitles(robot: FxRobot): List<String> =
        robot
            .lookup(".editor-tab .label")
            .queryAllAs(Label::class.java)
            .sortedBy { it.localToScene(0.0, 0.0).x }
            .map { it.text }

    /** The text of the shown file view, unless it is a diff. */
    internal fun shownText(robot: FxRobot): String? =
        robot
            .lookup(".editor-content .code-editor .code-area")
            .queryAllAs(CodeArea::class.java)
            // The editor stays below a diff shown over it, hidden.
            .singleOrNull { area -> generateSequence<Node>(area) { it.parent }.all { it.isVisible } }
            ?.text

    internal fun tabLabel(
        robot: FxRobot,
        name: String,
    ): Label = robot.lookup(".editor-tab .label").queryAllAs(Label::class.java).single { it.text == name }

    /** Sends a press/release/click through the scene graph without OS input, see docs/testing.md. */
    internal fun click(
        node: Node,
        button: MouseButton = MouseButton.PRIMARY,
    ) {
        for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            fireMouse(node, type, button, buttonDown = type == MouseEvent.MOUSE_PRESSED)
        }
    }

    /** Fires a mouse event at [node] as if the pointer were at [sceneX], [sceneY], without OS input. */
    internal fun fireMouse(
        node: Node,
        type: EventType<MouseEvent>,
        button: MouseButton = MouseButton.PRIMARY,
        sceneX: Double = 1.0,
        sceneY: Double = 1.0,
        buttonDown: Boolean = true,
    ) {
        Event.fireEvent(
            node,
            MouseEvent(
                type,
                sceneX,
                sceneY,
                sceneX,
                sceneY,
                button,
                1,
                false,
                false,
                false,
                false,
                buttonDown && button == MouseButton.PRIMARY,
                buttonDown && button == MouseButton.MIDDLE,
                false,
                false,
                false,
                true,
                null,
            ),
        )
    }

    /**
     * Presses the primary button on the middle of [node] and moves the mouse by [dx] in small steps, then releases it
     * unless [release] is `false`; the events are fired at [node].
     */
    internal fun dragBy(
        robot: FxRobot,
        node: Node,
        dx: Double,
        release: Boolean = true,
    ) {
        val bounds = node.layoutBounds
        val start = node.localToScene(bounds.centerX, bounds.centerY)
        val steps = (abs(dx) / 5).toInt().coerceAtLeast(1)
        robot.interact {
            fireMouse(node, MouseEvent.MOUSE_PRESSED, sceneX = start.x, sceneY = start.y)
            for (step in 1..steps) {
                fireMouse(node, MouseEvent.MOUSE_DRAGGED, sceneX = start.x + dx * step / steps, sceneY = start.y)
            }
        }
        WaitForAsyncUtils.waitForFxEvents()
        if (release) releaseOn(robot, node, start.x + dx)
    }

    internal fun releaseOn(
        robot: FxRobot,
        node: Node,
        sceneX: Double,
    ) {
        robot.interact { fireMouse(node, MouseEvent.MOUSE_RELEASED, sceneX = sceneX, buttonDown = false) }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** Sends a context menu request through the scene graph, like a right-click, without OS input. */
    internal fun requestContextMenu(node: Node) {
        val bounds = node.localToScreen(node.boundsInLocal)
        Event.fireEvent(
            node,
            ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                1.0,
                1.0,
                bounds.minX + 1,
                bounds.minY + 1,
                false,
                null,
            ),
        )
    }

    /**
     * Puts a text field above the editor area, standing in for the file tree, to have the focus outside of it. With
     * [viewers], the area is a new one that shows files with them, before the default text viewer.
     */
    internal fun fieldAbove(
        robot: FxRobot,
        viewers: List<FileViewer>? = null,
    ): TextField {
        val field = TextField()
        robot.interact {
            stage.scene.root = Pane()
            if (viewers != null) {
                val fallback = DefaultTextFileViewer()
                editor = EditorArea(FileViewers(listOf(fallback) + viewers, fallback), watcher, fontSettings)
            }
            stage.scene = Scene(VBox(field, editor), 800.0, 400.0)
        }
        return field
    }

    /** The node with the focus in the stage. */
    internal fun focusOwner(): Node? = WaitForAsyncUtils.asyncFx<Node?> { stage.scene.focusOwner }.get()

    /** The text area of the shown file view, unless it is a diff. */
    internal fun shownArea(robot: FxRobot): CodeArea =
        robot.lookup(".editor-content .code-editor .code-area").queryAs(CodeArea::class.java)

    /** Opens `.unsavable` files in an edited view whose saving always fails. */
    internal class UnsavableViewer : FileViewer {
        override fun canOpen(file: Path) = file.toString().endsWith(".unsavable")

        override fun open(file: Path): FileView =
            object : EditableFileView {
                override val node = Label("edited")
                override val dirty = SimpleBooleanProperty(true)

                override fun reload() {}

                override fun save() = throw IOException("Disk full")
            }
    }

    /** The texts of the code areas of the shown diff, left to right; empty if no diff is shown. */
    internal fun shownDiff(robot: FxRobot): List<String> =
        robot
            .lookup(".editor-content .diff-view .code-area")
            .queryAllAs(CodeArea::class.java)
            .sortedBy { it.localToScene(0.0, 0.0).x }
            .map { it.text }

    /** The text of the banner shown above the selected file's text, if any. */
    internal fun bannerText(robot: FxRobot): String? =
        robot
            .lookup(".editor-content .editor-banner .label")
            .queryAllAs(Label::class.java)
            .singleOrNull()
            ?.text

    /** Moves [from] to [to] on disk and tells the editor area, in one go on the FX thread, as the explorer does. */
    internal fun move(
        robot: FxRobot,
        from: Path,
        to: Path,
    ) {
        robot.interact {
            from.moveTo(to)
            editor.moved(from, to)
        }
    }

    internal class WarningTab(
        name: String,
        override var closeWarning: String?,
    ) : EditorTab {
        override val title = SimpleStringProperty(name)
        override val tooltip = name
        override val content = Label(name)
    }

    /** Opens [files], edits each and records what [EditorArea.confirmSave] is asked, answering [answer]. */
    internal fun openEdited(
        robot: FxRobot,
        vararg files: Path,
        answer: () -> SaveChoice,
    ): List<List<String>> {
        val asked = mutableListOf<List<String>>()
        editor.projectFolder = { dir }
        editor.confirmSave = { names ->
            asked += names
            answer()
        }
        for (file in files) {
            open(robot, file)
            robot.interact { shownArea(robot).insertText(0, "edited ") }
        }
        return asked
    }

    /** Repeats [assertion] on the FX thread until it passes; the JDK may poll for file changes on macOS. */
    internal fun eventually(
        robot: FxRobot,
        assertion: () -> Unit,
    ) {
        val deadline = System.nanoTime() + 20.seconds.inWholeNanoseconds
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            var failure: AssertionError? = null
            robot.interact {
                try {
                    assertion()
                } catch (e: AssertionError) {
                    failure = e
                }
            }
            val error = failure ?: return
            if (System.nanoTime() > deadline) throw error
            Thread.sleep(50)
        }
    }
}
