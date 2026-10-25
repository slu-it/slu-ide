package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.ClipboardFiles
import dev.sluide.fileexplorer.ExplorerClipboard
import dev.sluide.fileexplorer.tree.FileTreeItem
import javafx.scene.control.TreeItem
import java.nio.file.Files
import java.nio.file.Path

/**
 * An [ExplorerEditHost] without a view, for testing [ExplorerEdits], [ExplorerMoves] and [ExplorerCopies] without a
 * display: a hidden root holding the project folder and the extra roots, every callback recorded, the answers of the
 * questions set by the test, and a clipboard in memory. The tree items work without the JavaFX toolkit.
 */
internal class FakeEditHost(
    private val project: Path,
    private val extraRoots: List<Path> = emptyList(),
) {
    private var root = TreeItem<Path>()

    lateinit var projectItem: FileTreeItem
        private set
    lateinit var extraItems: List<FileTreeItem>
        private set

    // Answers; they may change the disk first, as can happen while a dialog is open.
    var answerMove: () -> Boolean = { true }
    var answerDelete: () -> Boolean = { true }
    var answerCopy: () -> Boolean = { true }
    var answerName: (String) -> String? = { null }
    var unsaved: (Path) -> List<Path> = { emptyList() }

    /** Runs background work at once; otherwise it waits in [pending]. */
    var inline = true
    val pending = mutableListOf<Runnable>()

    val errors = mutableListOf<String>()
    val infos = mutableListOf<String>()
    val moveQuestions = mutableListOf<MoveQuestion>()
    val copyQuestions = mutableListOf<CopyQuestion>()
    val deleteQuestions = mutableListOf<DeleteQuestion>()
    val nameAsks = mutableListOf<NameAsk>()
    val moved = mutableListOf<Pair<Path, Path>>()
    val deleted = mutableListOf<Pair<Path, List<Path>>>()
    val opened = mutableListOf<Path>()
    val closedUnder = mutableListOf<Path>()
    var vanishedChecks = 0

    /** The selected items; `select`, `selectRow` and `selectRows` set it, as the view does. */
    var selected: List<TreeItem<Path>> = emptyList()
    val selectRowCalls = mutableListOf<TreeItem<Path>>()
    val selectRowsCalls = mutableListOf<List<TreeItem<Path>>>()

    class NameAsk(
        val title: String,
        val initial: String,
        val validate: (String) -> String?,
    )

    val host =
        ExplorerEditHost(
            projectItem = { projectItem },
            askName = { title, initial, validate ->
                nameAsks += NameAsk(title, initial, validate)
                answerName(initial)
            },
            showError = { errors += it },
            showInfo = { infos += it },
            confirmDelete = {
                deleteQuestions += it
                answerDelete()
            },
            confirmMove = {
                moveQuestions += it
                answerMove()
            },
            confirmCopy = {
                copyQuestions += it
                answerCopy()
            },
            runInBackground = { if (inline) it.run() else pending += it },
            dispatch = { it.run() },
            onMoved = { from, to -> moved += from to to },
            unsavedUnder = { unsaved(it) },
            onDeleted = { path, lost -> deleted += path to lost },
            requestOpen = { path ->
                Files.isRegularFile(path).also { if (it) opened.add(path) }
            },
            select = { parent, path ->
                parent.isExpanded = true
                parent.refresh()
                parent.children.firstOrNull { it.value == path }?.also(::selectRow)
            },
            selectRow = ::selectRow,
            selectRows = { items ->
                selectRowsCalls += items
                // As the view: nothing to select keeps the selection.
                if (items.isNotEmpty()) {
                    val rows = rows()
                    selected = items.sortedBy { rows.indexOf(it) }
                }
            },
            selectedItems = { selected },
            rows = ::rows,
            isShown = ::isShown,
            closeWatchesUnder = { closedUnder.add(it) },
            closeWatchesOfVanishedFolders = { vanishedChecks++ },
        )

    val edits = ExplorerEdits(host)
    val moves = ExplorerMoves(host, edits::canChange, edits::isBeingDeleted, edits::canChangePath)

    /** The clipboard the copies use, on [clipboardFiles] instead of the system clipboard. */
    val clipboardFiles = FakeClipboardFiles()
    val clipboard = ExplorerClipboard(clipboardFiles)
    val copies = ExplorerCopies(host, moves, { clipboard }, edits::canChange, edits::isBeingDeleted)

    init {
        reopen()
    }

    /** Opens the folders again as new items; the old ones are no longer shown. */
    fun reopen() {
        projectItem = FileTreeItem(project).also { it.isExpanded = true }
        extraItems = extraRoots.map { FileTreeItem(it).also { item -> item.isExpanded = true } }
        root = TreeItem<Path>()
        root.children.setAll(listOf(projectItem) + extraItems)
    }

    private fun selectRow(item: TreeItem<Path>) {
        selectRowCalls += item
        selected = listOf(item)
    }

    private fun isShown(item: TreeItem<Path>) = generateSequence(item) { it.parent }.last() === root

    private fun rows(): List<TreeItem<Path>> {
        fun flatten(item: TreeItem<Path>): List<TreeItem<Path>> =
            listOf(item) + if (item.isExpanded) item.children.flatMap(::flatten) else emptyList()
        return root.children.flatMap(::flatten)
    }

    /** The shown entry at [name], a path relative to its top-level folder ([top], the project by default). */
    fun item(
        name: String,
        top: FileTreeItem = projectItem,
    ): FileTreeItem =
        name.split('/').fold(top as TreeItem<Path>) { folder, segment ->
            folder.children.first { it.value.fileName.toString() == segment }
        } as FileTreeItem

    /** Selects [names] like a click, without a selection call being recorded. */
    fun select(vararg names: String) {
        selected = names.map { item(it) }
    }

    /** The selected paths in row order. */
    fun selectedPaths(): List<Path> = selected.map { it.value }
}

/** A clipboard in memory: the files put on it, `null` for something else (e.g. text). */
internal class FakeClipboardFiles : ClipboardFiles {
    var content: List<Path>? = null

    override fun files(): List<Path> = content.orEmpty()

    override fun put(files: List<Path>) {
        content = files
    }

    override fun clear() {
        content = null
    }
}
