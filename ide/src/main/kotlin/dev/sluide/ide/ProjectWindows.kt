package dev.sluide.ide

import dev.sluide.projects.ProjectPicker
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.stage.Stage
import javafx.stage.WindowEvent
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

private val log = System.getLogger(ProjectWindows::class.java.name)

/**
 * The open IDE windows, one project folder each (ADR 0014). Every window has its own window-scoped beans
 * ([WindowScope]): main window, explorer, editor area, terminals, …; they are destroyed when the window is closed.
 * Without a window, the project picker is shown: at startup without folders, when the last window closes and when a
 * folder fails to open; the application ends only when the picker is cancelled while no window is open (ADR 0034).
 * Used on the JavaFX thread.
 */
@Component
class ProjectWindows(
    private val beanFactory: ConfigurableListableBeanFactory,
    private val picker: ProjectPicker,
) : DisposableBean {
    private class OpenWindow(
        val stage: Stage,
        val window: MainWindow,
        val beans: WindowBeans,
    )

    private val scope get() = beanFactory.getRegisteredScope(WINDOW_SCOPE) as WindowScope
    private val open = mutableListOf<OpenWindow>()

    /** Set while the application ends, so closing its windows does not show the picker. */
    private var stopping = false

    /** The last picker shown, so a second one is not stacked on it. */
    private var pickerStage: Stage? = null

    /** Ends the application; tests replace it. */
    internal var exit: () -> Unit = Platform::exit

    /** Shows an error message and waits until it is closed; tests replace it, since it blocks. */
    internal var showError: (String) -> Unit = { message ->
        Alert(Alert.AlertType.ERROR, message).apply { headerText = null }.showAndWait()
    }

    /** Whether a folder to open still exists; tests replace it to make a folder vanish right after this check. */
    internal var isFolder: (Path) -> Boolean = Files::isDirectory

    /** Releases what a closed window still holds; tests replace it to make it fail. */
    internal var disposeWindow: (MainWindow) -> Unit = MainWindow::dispose

    /** The stages of the open windows, oldest first. */
    val stages: List<Stage> get() = open.map { it.stage }

    /** The main window shown in [stage], for tests. */
    internal fun windowOf(stage: Stage): MainWindow = open.single { it.stage == stage }.window

    /**
     * Opens the startup [folders]: the first in [stage], every other one in a new window. Without folders, [stage]
     * stays unused and the project picker is shown instead, as it is when none of the folders opens.
     */
    fun start(
        stage: Stage,
        folders: List<Path>,
    ) {
        if (folders.isEmpty()) {
            showPicker()
            return
        }
        try {
            tryOpening(folders.first()) { show(stage, it) }
            folders.drop(1).forEach { folder -> tryOpening(folder, ::open) }
        } finally {
            showPickerWithoutWindow()
        }
    }

    /**
     * Shows the project picker: the chosen folder opens like [open]; cancelling it while no window is open ends the
     * application. If the chosen folder does not open, the picker is shown again.
     */
    fun showPicker() {
        pickerStage =
            picker.show(
                onOpen = { folder ->
                    try {
                        tryOpening(folder, ::open)
                    } finally {
                        showPickerWithoutWindow()
                    }
                },
                onCancel = { if (open.isEmpty()) exit() },
            )
    }

    /**
     * Opens [folder] with [opening]; an exception is logged and shown, naming the folder. Errors are passed on: the
     * callers show the picker in `finally` if no window is showing.
     */
    private fun tryOpening(
        folder: Path,
        opening: (Path) -> Unit,
    ) {
        try {
            opening(folder)
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "Cannot open $folder", e)
            showError("$folder cannot be opened. ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Shows the picker unless a window or a picker is showing or the application ends, so it never runs without. */
    private fun showPickerWithoutWindow() {
        if (stopping || open.any { it.stage.isShowing } || pickerStage?.isShowing == true) return
        showPicker()
    }

    /** Shows a new window in [stage], with [folder] opened if it is not `null`. */
    fun show(
        stage: Stage,
        folder: Path? = null,
    ): MainWindow {
        val beans = WindowBeans()
        var entry: OpenWindow? = null
        try {
            val window = scope.inWindow(beans) { beanFactory.getBean(MainWindow::class.java) }
            val opened = OpenWindow(stage, window, beans)
            entry = opened
            open += opened
            stage.addEventHandler(WindowEvent.WINDOW_HIDDEN) { close(opened) }
            window.show(stage)
            folder?.let(window::openFolder)
            return window
        } catch (e: Throwable) {
            // No dead window stays open and no beans of it are left, even of a window that was created only in part.
            // Removed first, so hiding it does not show the picker; the callers do if no window is showing.
            entry?.let(open::remove)

            fun cleanUp(step: () -> Unit) = runCatching(step).exceptionOrNull()?.let(e::addSuppressed)
            cleanUp(stage::hide)
            entry?.let { cleanUp { disposeWindow(it.window) } }
            cleanUp { scope.destroy(beans) }
            throw e
        }
    }

    /** Brings the window showing [folder] to the front, or opens [folder] in a new window. */
    fun open(folder: Path) {
        val path = folder.toAbsolutePath().normalize()
        open.firstOrNull { it.window.folder == path }?.let {
            it.stage.toFront()
            it.stage.requestFocus()
            return
        }
        // Checked before a stage is made, so a folder that vanished leaves no empty window behind.
        if (!isFolder(path)) {
            showError("$path cannot be opened: the folder no longer exists.")
            showPickerWithoutWindow()
            return
        }
        val stage = Stage()
        // Cascade from the focused window, so the new one does not hide it exactly.
        (open.firstOrNull { it.stage.isFocused } ?: open.lastOrNull())?.stage?.let { previous ->
            stage.x = previous.x + CASCADE
            stage.y = previous.y + CASCADE
        }
        show(stage, path)
    }

    private fun close(entry: OpenWindow) {
        if (!open.remove(entry)) return
        try {
            try {
                disposeWindow(entry.window)
            } finally {
                scope.destroy(entry.beans)
            }
        } finally {
            // Also after a failure, since without implicit exit the application would run on without any window.
            if (open.isEmpty()) showPickerWithoutWindow()
        }
    }

    /**
     * Destroys the beans of windows that are still open when the application ends. Not when the JVM's shutdown hook
     * closes the context (SIGTERM, Ctrl+C): JavaFX ends its toolkit in a hook of its own at the same time, so the
     * windows can neither be closed on that thread nor handed to the JavaFX thread. What they hold ends with the
     * process: a terminal's shell gets a hangup when its pty closes. Only a git process still running (a push, a
     * commit) is not stopped and finishes on its own, which a write is better off doing than being killed halfway.
     */
    override fun destroy() {
        stopping = true
        if (!Platform.isFxApplicationThread()) return
        open.toList().forEach(::close)
    }
}

private const val CASCADE = 30.0
