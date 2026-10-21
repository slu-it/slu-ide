package dev.sluide.projects

import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.event.Event
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import org.kordamp.ikonli.javafx.FontIcon
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * The window shown at start-up when no project is given and after the last project window closes: pick a recent
 * project or open a new folder.
 */
@Component
class ProjectPicker(
    private val recentProjects: RecentProjects,
) {
    internal var home: Path = Path.of(System.getProperty("user.home"))

    internal var chooseFolder: (Stage) -> Path? = { owner ->
        DirectoryChooser()
            .apply {
                title = "Open Project"
                if (Files.isDirectory(home)) initialDirectory = home.toFile()
            }.showDialog(owner)
            ?.toPath()
    }

    /** One row: [project] is `null` for "Open new project…". */
    private data class Entry(
        val project: RecentProject?,
        val missing: Boolean = false,
    )

    /**
     * Shows a new picker window without blocking. Exactly one of the callbacks is called: [onOpen] with the chosen
     * folder, or [onCancel] when the window is closed or Escape is pressed. The window is titled [title].
     */
    fun show(
        onOpen: (Path) -> Unit,
        onCancel: () -> Unit,
        title: String = "Thoreau",
    ): Stage {
        val stage = Stage()
        var opening = false
        val entries: ObservableList<Entry> = FXCollections.observableArrayList()
        entries.add(Entry(null))
        // existence is checked on the FX thread: the list is small, but a slow network mount could delay the window
        recentProjects.list().forEach { entries.add(Entry(it, !Files.isDirectory(it.folder))) }

        fun open(folder: Path) {
            if (!stage.isShowing) return
            opening = true
            stage.hide()
            onOpen(folder)
        }

        val list = ListView(entries)
        list.styleClass.add("project-picker")

        fun remove(entry: Entry) {
            val project = entry.project ?: return
            recentProjects.remove(project.folder)
            entries.remove(entry)
        }

        fun activate(entry: Entry) {
            val project = entry.project
            when {
                project == null -> chooseFolder(stage)?.let(::open)
                entry.missing -> Unit
                Files.isDirectory(project.folder) -> open(project.folder)
                else -> entries.indexOf(entry).takeIf { it >= 0 }?.let { entries[it] = entry.copy(missing = true) }
            }
        }

        list.setCellFactory { Cell(::activate, ::remove, home) }
        list.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            val selected = list.selectionModel.selectedItem
            when (event.code) {
                KeyCode.ENTER -> {
                    selected?.let(::activate)
                }

                KeyCode.DELETE, KeyCode.BACK_SPACE -> {
                    val index = list.selectionModel.selectedIndex
                    selected?.takeIf { it.project != null }?.let(::remove)
                    // keep the selection at the same row, so the next entry can be removed right away
                    list.selectionModel.select(index.coerceAtMost(entries.size - 1))
                }

                else -> {
                    return@addEventFilter
                }
            }
            event.consume()
        }

        val scene = Scene(list, 520.0, 460.0)
        scene.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.ESCAPE) {
                event.consume()
                stage.hide()
            }
        }
        scene.stylesheets.add(checkNotNull(ProjectPicker::class.java.getResource("projects.css")).toExternalForm())

        stage.title = title
        stage.scene = scene
        stage.minWidth = 360.0
        stage.minHeight = 280.0
        stage.setOnHidden { if (!opening) onCancel() }
        list.selectionModel.select(0)
        stage.centerOnScreen()
        stage.show()
        list.requestFocus()
        return stage
    }

    private class Cell(
        private val activate: (Entry) -> Unit,
        private val remove: (Entry) -> Unit,
        private val home: Path,
    ) : ListCell<Entry>() {
        init {
            // The list's width decides the cells' width, not their content: no horizontal scroll bar for long paths.
            prefWidth = 0.0
            setOnMouseClicked { event ->
                if (event.button == MouseButton.PRIMARY && event.clickCount == 1) item?.let(activate)
            }
        }

        override fun updateItem(
            entry: Entry?,
            empty: Boolean,
        ) {
            super.updateItem(entry, empty)
            styleClass.remove("missing")
            if (empty || entry == null) {
                graphic = null
                return
            }
            if (entry.missing) styleClass.add("missing")
            graphic = entry.project?.let { project(it, entry) } ?: newProject()
        }

        private fun newProject(): HBox {
            val label = Label("Open new project…").apply { styleClass.add("project-name") }
            return HBox(8.0, FontIcon("mdi2f-folder-plus-outline"), label).apply { alignment = Pos.CENTER_LEFT }
        }

        private fun project(
            project: RecentProject,
            entry: Entry,
        ): HBox {
            val path = project.displayPath(home) + if (entry.missing) " — folder not found" else ""
            val texts =
                VBox(
                    Label(project.name).apply { styleClass.add("project-name") },
                    Label(path).apply { styleClass.add("project-path") },
                )
            val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
            val delete =
                Button().apply {
                    graphic = FontIcon("mdi2d-delete-outline")
                    styleClass.add("project-remove")
                    tooltip = Tooltip("Remove from list")
                    isFocusTraversable = false
                    // a click on the button must not reach the cell, which would open the project
                    addEventHandler(MouseEvent.MOUSE_CLICKED, Event::consume)
                    setOnAction { remove(entry) }
                }
            return HBox(texts, spacer, delete).apply {
                alignment = Pos.CENTER_LEFT
                padding = Insets(2.0, 0.0, 2.0, 0.0)
            }
        }
    }
}
