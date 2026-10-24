package dev.sluide.gradle.view

import dev.sluide.gradle.tasks.GradleProject
import dev.sluide.gradle.tasks.GradleTask
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SelectionMode
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/** What the task pane shows instead of the tree. */
internal enum class TaskNote(
    val text: String,
) {
    LOADING("Loading tasks…"),
    FAILED("Could not load tasks — see the output below."),
    UNTRUSTED("Gradle is off until the folder is trusted."),
}

/** Above the tree once a build file was saved after the tasks were loaded. */
internal const val STALE_NOTE = "Build files changed since loading — Refresh to reload."

/**
 * The upper half of the Gradle side: a filter, Refresh, and the tasks as project → group → task ([taskTree]), or a note
 * ([TaskNote]) in their place. Double-click on a task or Enter runs the selected tasks ([onRun]). FX thread.
 */
internal class TaskTreePane {
    /** Runs the task paths, in tree order. */
    var onRun: (List<String>) -> Unit = {}

    var onRefresh: () -> Unit = {}

    /** Review… of the trust note. */
    var onReview: () -> Unit = {}

    val filter =
        TextField().apply {
            promptText = "Filter tasks"
            styleClass += "gradle-filter"
            HBox.setHgrow(this, Priority.ALWAYS)
        }

    val refresh = Button("Refresh").apply { setOnAction { onRefresh() } }

    val tree =
        TreeView<Any>(TreeItem()).apply {
            isShowRoot = false
            selectionModel.selectionMode = SelectionMode.MULTIPLE
            styleClass += "gradle-tasks"
            setCellFactory { TaskCell() }
        }

    val stale =
        Label(STALE_NOTE).apply {
            styleClass += "gradle-stale"
            isWrapText = true
            isVisible = false
            isManaged = false
        }

    private val noteText = Label().apply { isWrapText = true }
    private val review = Button("Review…").apply { setOnAction { onReview() } }
    private val note =
        VBox(noteText, review).apply {
            styleClass += "gradle-note"
            alignment = Pos.TOP_LEFT
        }

    private val content = StackPane(tree)

    val node =
        VBox(
            HBox(filter, refresh).apply { styleClass += "gradle-task-bar" },
            stale,
            content,
        ).apply {
            styleClass += "gradle-task-pane"
            VBox.setVgrow(content, Priority.ALWAYS)
            minHeight = 0.0
        }

    /** The note shown instead of the tree, if any. */
    var shownNote: TaskNote? = null
        private set

    private var projects: List<GradleProject> = emptyList()

    init {
        filter.textProperty().addListener { _, _, _ -> render() }
        tree.addEventHandler(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.ENTER) {
                val tasks = selectedTasks()
                if (tasks.isNotEmpty()) onRun(tasks)
                event.consume()
            }
        }
    }

    /** Shows [projects] (a subtree, the view's own project first among its ancestors) as the tree. */
    fun showTasks(projects: List<GradleProject>) {
        this.projects = projects
        shownNote = null
        content.children.setAll(tree)
        render()
    }

    fun showNote(note: TaskNote) {
        shownNote = note
        noteText.text = note.text
        review.isVisible = note == TaskNote.UNTRUSTED
        review.isManaged = note == TaskNote.UNTRUSTED
        content.children.setAll(this.note)
    }

    fun showStale(shown: Boolean) {
        stale.isVisible = shown
        stale.isManaged = shown
    }

    /** The paths of the selected tasks in the order of the rows; projects and groups do not count. */
    fun selectedTasks(): List<String> =
        tree.selectionModel.selectedItems
            .filter { it?.value is GradleTask }
            .sortedBy { tree.getRow(it) }
            .map { (it.value as GradleTask).path }

    private fun render() {
        val entries = taskTree(projects, ownProject(projects), filter.text)
        tree.root.children.setAll(
            entries.map { project ->
                TreeItem<Any>(project).apply {
                    isExpanded = project.expanded
                    children.setAll(
                        project.groups.map { group ->
                            TreeItem<Any>(group).apply {
                                isExpanded = group.expanded
                                children.setAll(group.tasks.map { TreeItem<Any>(it) })
                            }
                        },
                    )
                }
            },
        )
    }

    /** A task's name with its description dimmed after it; projects and groups by name. */
    private inner class TaskCell : TreeCell<Any>() {
        private val name = Label()
        private val description = Label().apply { styleClass += "gradle-task-description" }
        private val box = HBox(name, description).apply { styleClass += "gradle-task" }

        init {
            setOnMouseClicked { event ->
                if (event.button == MouseButton.PRIMARY && event.clickCount == 2 && item is GradleTask) {
                    onRun(selectedTasks())
                    event.consume()
                }
            }
        }

        override fun updateItem(
            item: Any?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = null
            graphic = null
            tooltip = null
            styleClass.removeAll(ROW_CLASSES)
            when (item) {
                is TreeProject -> {
                    text = item.label
                    styleClass += "gradle-project"
                }

                is TreeGroup -> {
                    text = item.name
                    styleClass += "gradle-group"
                }

                is GradleTask -> {
                    name.text = item.name
                    description.text = item.description.orEmpty()
                    graphic = box
                    tooltip = Tooltip(listOfNotNull(item.path, item.description).joinToString("\n"))
                }
            }
        }
    }

    private companion object {
        val ROW_CLASSES = listOf("gradle-project", "gradle-group")
    }
}
