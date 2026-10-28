package dev.sluide.backgroundtasks

import javafx.beans.InvalidationListener
import javafx.beans.binding.Bindings
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC

/** At most this many tasks get a row; the rest are counted in a "+N more" row (ADR 0061). */
internal const val MAX_ROWS = 4

private const val PROGRESS_WIDTH = 70.0

/** The width kept for ✕ in every row. */
private const val CANCEL_WIDTH = 18.0

/**
 * The window's [shown][BackgroundTasks.shown] background tasks as an inline list (ADR 0061): one row per task with its
 * name, an indeterminate progress bar and, if it can be cancelled, ✕; at most [MAX_ROWS] rows plus "+N more". Hidden
 * and unmanaged while no task is shown.
 */
class BackgroundTasksView(
    private val tasks: BackgroundTasks,
) : VBox() {
    /** The rows of the tasks that have one, kept while they do so the progress bars keep running. */
    private val rows = mutableMapOf<BackgroundTask, Row>()

    private val more = Label().apply { styleClass += "background-tasks-more" }

    init {
        styleClass += "background-tasks"
        // Never widens the explorer: the names and bars shrink instead.
        minWidth = 0.0
        stylesheets +=
            checkNotNull(BackgroundTasksView::class.java.getResource("background-tasks.css")).toExternalForm()
        val any = Bindings.isNotEmpty(tasks.shown)
        visibleProperty().bind(any)
        managedProperty().bind(any)
        tasks.shown.addListener(InvalidationListener { update() })
        update()
    }

    private fun update() {
        val listed = tasks.shown.take(MAX_ROWS)
        (rows.keys - listed.toSet()).forEach { rows.remove(it)?.dispose() }
        val nodes: MutableList<Node> = listed.mapTo(mutableListOf()) { task -> rows.getOrPut(task) { Row(task) }.node }
        val hidden = tasks.shown.size - listed.size
        if (hidden > 0) {
            more.text = "+$hidden more"
            nodes += more
        }
        children.setAll(nodes)
    }

    private inner class Row(
        task: BackgroundTask,
    ) {
        private val label =
            Label().apply {
                styleClass += "background-task-name"
                textProperty().bind(task.name)
                maxWidth = Double.MAX_VALUE
                minWidth = 0.0
                HBox.setHgrow(this, Priority.ALWAYS)
                // The elapsed time, as of when the tooltip is shown.
                tooltip =
                    Tooltip().apply {
                        setOnShowing { text = "${task.name.get()}\nRunning for ${seconds(tasks.elapsed(task))}" }
                    }
            }

        private val progress =
            ProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS).apply {
                prefWidth = PROGRESS_WIDTH
                // Gives way in a narrow explorer, like the name.
                minWidth = 0.0
                maxWidth = Region.USE_PREF_SIZE
            }

        /**
         * Holds ✕ while the task can be cancelled, else stays empty, so the progress bars of all rows line up; ✕ goes
         * when the task withdraws its cancel.
         */
        private val cancelSlot =
            StackPane().apply {
                minWidth = CANCEL_WIDTH
                prefWidth = CANCEL_WIDTH
                maxWidth = CANCEL_WIDTH
                if (task.cancellable) {
                    children +=
                        Button(null, FontIcon(MaterialDesignC.CLOSE)).apply {
                            styleClass += "background-task-cancel"
                            tooltip = Tooltip("Cancel")
                            isFocusTraversable = false
                            setOnAction { task.cancel() }
                            visibleProperty().bind(task.cancellableProperty)
                        }
                }
            }

        val node: HBox =
            HBox(label, progress, cancelSlot).apply {
                styleClass += "background-task"
                alignment = Pos.CENTER_LEFT
            }

        /** Lets go of the task's name; the progress bar stops when its row leaves the scene. */
        fun dispose() = label.textProperty().unbind()
    }
}
