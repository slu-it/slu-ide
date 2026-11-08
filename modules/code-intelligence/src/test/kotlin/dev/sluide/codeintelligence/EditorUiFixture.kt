package dev.sluide.codeintelligence

import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.CodeEditorExtensions
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.WindowServices
import dev.sluide.fileviewers.languages.PythonFileViewer
import javafx.event.Event
import javafx.geometry.Bounds
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.stage.Popup
import javafx.stage.Stage
import javafx.stage.Window
import org.testfx.api.FxRobot
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/** A real Python code view in a window, extended by the extensions a test gives, for the UI tests of this module. */
internal abstract class EditorUiFixture {
    protected val container = StackPane()
    protected lateinit var stage: Stage
    protected var view: FileView? = null

    protected fun startStage(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(container, 600.0, 400.0)
        stage.show()
    }

    protected fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(Callable { block() }).get(5, TimeUnit.SECONDS)

    /** A host of a window with [services] and the [extensions] (registered as the code editors' window service). */
    protected fun host(
        extensions: List<CodeEditorExtension>,
        vararg services: Pair<Class<*>, Any>,
    ): FileViewHost {
        val registry =
            WindowServices().apply {
                register(CodeEditorExtensions::class.java, CodeEditorExtensions().apply { extensions.forEach(::add) })
                @Suppress("UNCHECKED_CAST")
                services.forEach { (type, service) -> register(type as Class<Any>, service) }
            }
        return object : FileViewHost {
            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override fun <T : Any> windowService(type: Class<T>): T? = registry.get(type)
        }
    }

    /**
     * [file] with [text] opened in a Python code view shown in the window and extended by [extensions] after one that
     * hands over the editor; the editor.
     */
    protected fun open(
        robot: FxRobot,
        file: Path,
        text: String,
        extensions: List<CodeEditorExtension>,
        vararg services: Pair<Class<*>, Any>,
    ): ExtensibleCodeEditor {
        file.writeText(text)
        var editor: ExtensibleCodeEditor? = null
        val capture =
            CodeEditorExtension {
                editor = it
                null
            }
        val opened = fx { PythonFileViewer().open(file) }
        view = opened
        robot.interact {
            container.children.setAll(opened.node)
            opened.host = host(listOf(capture) + extensions, *services)
        }
        return checkNotNull(editor) { "the view was not extended" }
    }

    /** The text area of the view. */
    protected fun area(): Node = fx { checkNotNull(view?.node?.lookup(".styled-text-area")) }

    /** Presses [code] on the text area, as a key press reaches it (filters first). */
    protected fun press(
        robot: FxRobot,
        code: KeyCode,
    ) {
        val area = area()
        robot.interact {
            Event.fireEvent(area, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
        }
    }

    /** The mouse moves onto the screen point ([screenX], [screenY]) over the text area. */
    protected fun mouseMove(
        robot: FxRobot,
        screenX: Double,
        screenY: Double,
    ) {
        val area = area()
        robot.interact {
            val local = area.screenToLocal(screenX, screenY)
            Event.fireEvent(
                area,
                MouseEvent(
                    MouseEvent.MOUSE_MOVED,
                    local.x,
                    local.y,
                    screenX,
                    screenY,
                    MouseButton.NONE,
                    0,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    null,
                ),
            )
        }
    }

    /** The popups on screen whose content has [styleClass]. */
    protected fun popups(styleClass: String): List<Popup> =
        fx {
            Window.getWindows().filterIsInstance<Popup>().filter { popup ->
                popup.isShowing && popup.content.any { styleClass in it.styleClass }
            }
        }

    /** The screen bounds of [start, end) in [editor]. */
    protected fun bounds(
        editor: ExtensibleCodeEditor,
        start: Int,
        end: Int = start,
    ): Bounds = fx { checkNotNull(editor.screenBounds(start, end)) }

    protected fun closeView() {
        fx {
            view?.dispose()
            Window
                .getWindows()
                .filterIsInstance<Popup>()
                .toList()
                .forEach { it.hide() }
        }
    }
}
