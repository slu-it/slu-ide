package dev.sluide.ide

import javafx.stage.Stage
import javafx.stage.WindowEvent
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * The open IDE windows, one project folder each (ADR 0014). Every window has its own window-scoped beans
 * ([WindowScope]): main window, explorer, editor area, terminals, …; they are destroyed when the window is closed.
 * Used on the JavaFX thread.
 */
@Component
class ProjectWindows(
    private val beanFactory: ConfigurableListableBeanFactory,
) : DisposableBean {
    private class OpenWindow(
        val stage: Stage,
        val window: MainWindow,
        val beans: WindowBeans,
    )

    private val scope get() = beanFactory.getRegisteredScope(WINDOW_SCOPE) as WindowScope
    private val open = mutableListOf<OpenWindow>()

    /** The stages of the open windows, oldest first. */
    val stages: List<Stage> get() = open.map { it.stage }

    /** The main window shown in [stage], for tests. */
    internal fun windowOf(stage: Stage): MainWindow = open.single { it.stage == stage }.window

    /** Shows a new window in [stage], with [folder] opened if it is not `null`. */
    fun show(
        stage: Stage,
        folder: Path? = null,
    ): MainWindow {
        val beans = WindowBeans()
        val window = scope.inWindow(beans) { beanFactory.getBean(MainWindow::class.java) }
        val entry = OpenWindow(stage, window, beans)
        open += entry
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN) { close(entry) }
        window.show(stage)
        folder?.let(window::openFolder)
        return window
    }

    /** Brings the window showing [folder] to the front, or opens [folder] in a new window. */
    fun open(folder: Path) {
        val path = folder.toAbsolutePath().normalize()
        open.firstOrNull { it.window.folder == path }?.let {
            it.stage.toFront()
            it.stage.requestFocus()
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
        entry.window.dispose()
        scope.destroy(entry.beans)
    }

    /** Destroys the beans of windows that are still open when the application ends. */
    override fun destroy() = open.toList().forEach(::close)
}

private const val CASCADE = 30.0
