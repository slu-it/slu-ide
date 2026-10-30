package dev.sluide.fileexplorer

import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.MenuItem
import javafx.scene.control.TreeCell
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the `FileExplorerView*UiTest` classes share: the temporary folders, the view with a real watcher, and helpers to
 * read the rows and open the context menu. ApplicationExtension only finds `@Start` on the test class itself, so each
 * class declares it and calls [startView].
 */
abstract class FileExplorerViewUiFixture {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var settingsDir: Path

    protected lateinit var view: FileExplorerView
    protected lateinit var fontSettings: FontSettings

    /**
     * The settings of [view]. Compact Middle Folders starts off here, so tests about other things see one row per
     * folder; the tests of compacting turn it on.
     */
    protected lateinit var explorerSettings: ExplorerSettings
    protected val watcher = FileWatcher(Platform::runLater, 50.milliseconds)

    /** Creates [view] (set up by [configure] before it is shown) and shows it, or what [wrap] makes of it, in [stage]. */
    protected fun startView(
        stage: Stage,
        configure: (FileExplorerView) -> Unit = {},
        wrap: (FileExplorerView) -> Parent = { it },
    ) {
        val store = SettingsStore(settingsDir.resolve("settings.properties"))
        fontSettings = FontSettings(store, FontSettings.systemDefaults())
        explorerSettings = ExplorerSettings(store).apply { compactFolders.set(false) }
        view = FileExplorerView(watcher, fontSettings, ExplorerClipboard(), explorerSettings)
        configure(view)
        stage.scene = Scene(wrap(view), 400.0, 600.0)
        stage.show()
    }

    @AfterEach
    fun closeWatcher() = watcher.close()

    /** The texts of the shown rows, top to bottom. */
    protected fun renderedNames(robot: FxRobot): List<String> =
        robot
            .lookup(".tree-cell")
            .queryAllAs(TreeCell::class.java)
            .sortedBy { it.index } // lookups return cells in no particular order
            .mapNotNull { it.text }

    protected fun cellNamed(
        robot: FxRobot,
        name: String,
    ): TreeCell<*> =
        robot
            .lookup(".tree-cell")
            .queryAllAs(TreeCell::class.java)
            .first { it.text == name }

    /** Opens the context menu as a right-click on [node] (or the [keyboard]) does, and returns its item [text]. */
    protected fun menuItemFor(
        robot: FxRobot,
        node: Node,
        text: String,
        keyboard: Boolean = false,
    ): MenuItem {
        robot.interact {
            Event.fireEvent(
                node,
                ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1.0, 1.0, 1.0, 1.0, keyboard, null),
            )
        }
        val item = view.contextMenu.items.single { it.text == text }
        robot.interact { view.contextMenu.hide() }
        return item
    }

    /** Presses [code] on the view, with the shortcut key (Cmd on macOS, else Ctrl) if [shortcut], with Shift if [shift]. */
    protected fun pressKey(
        code: KeyCode,
        shortcut: Boolean = false,
        shift: Boolean = false,
    ) {
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        Event.fireEvent(
            view,
            KeyEvent(
                KeyEvent.KEY_PRESSED,
                "",
                "",
                code,
                shift,
                shortcut && !mac,
                false,
                shortcut && mac,
            ),
        )
    }

    /** Repeats [assertion] on the FX thread until it passes; the JDK may poll for file changes on macOS. */
    protected fun eventually(
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
