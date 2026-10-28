package dev.sluide.todos.view

import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.todos.TodoPanel
import dev.sluide.todos.TodoScope
import dev.sluide.todos.Todos
import javafx.scene.Node
import javafx.scene.Parent
import org.testfx.api.FxRobot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** A real [Todos] on a temporary store and panels whose dialogs are replaced by recording seams. */
@OptIn(ExperimentalPathApi::class)
internal class PanelFixture {
    val dir: Path = Files.createTempDirectory("todo-panel")
    val folder: Path = Files.createDirectories(dir.resolve("proj"))
    val todos = Todos(ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()))

    /** What the TODO dialog returns when [drafts] is empty. */
    var draft: TodoDraft? = null

    /** Answers of the TODO dialog, one per request, before [draft]. */
    val drafts = ArrayDeque<TodoDraft>()
    val requests = mutableListOf<TodoDialogRequest>()
    val errors = mutableListOf<String>()
    var allowDelete = false

    /** What the folder dialog returns when [folderDrafts] is empty. */
    var folderDraft: FolderDraft? = null
    val folderDrafts = ArrayDeque<FolderDraft>()
    val folderRequests = mutableListOf<FolderDialogRequest>()

    /** The answer of the folder delete confirmation. */
    var folderDeletion: FolderDeletion? = null
    val panels = mutableListOf<TodoPanel>()

    fun newPanel(projectFolder: Path? = folder): TodoPanel =
        TodoPanel(todos).also { panel ->
            panels += panel
            panel.open(projectFolder)
            panel.editTodo = {
                requests += it
                drafts.removeFirstOrNull() ?: draft
            }
            panel.editFolderDialog = {
                folderRequests += it
                folderDrafts.removeFirstOrNull() ?: folderDraft
            }
            panel.confirmDelete = { allowDelete }
            panel.confirmDeleteFolder = { folderDeletion }
            panel.showError = { errors += it }
        }

    /** Makes the project's data file one of a newer version: it is read but never written. */
    fun makeProjectReadOnly() {
        val file = Files.list(dir.resolve("projects")).use { it.toList().single() }
        Files.writeString(file, Files.readString(file).replace(Regex("\"version\"\\s*:\\s*1"), "\"version\": 2"))
    }

    fun cleanup() {
        panels.forEach(TodoPanel::dispose)
        dir.deleteRecursively()
    }

    /** Runs [block] on the FX thread (Todos and the scene graph are FX-thread only) and returns its result. */
    fun <T> fx(
        robot: FxRobot,
        block: () -> T,
    ): T {
        var result: Result<T>? = null
        robot.interact { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    fun descendants(node: Node): List<Node> =
        listOf(node) + ((node as? Parent)?.childrenUnmodifiable.orEmpty().flatMap(::descendants))

    fun summaries(
        robot: FxRobot,
        scope: TodoScope,
        at: Path? = folder,
    ): List<String> = fx(robot) { todos.list(scope, at).map { it.summary } }
}
