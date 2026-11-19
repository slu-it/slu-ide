package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.ClipboardFiles
import dev.sluide.fileexplorer.ClipboardImage
import dev.sluide.fileexplorer.ExplorerClipboard
import dev.sluide.fileexplorer.UnreadableClipboardImage
import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.fileexplorer.tree.TreeSearchFilter
import dev.sluide.fileexplorer.tree.expandTo
import dev.sluide.texttypes.TextType
import dev.sluide.texttypes.TextTypeDetector
import javafx.scene.control.TreeItem
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * An [ExplorerEditHost] without a view, for testing [ExplorerEdits], [ExplorerMoves] and [ExplorerCopies] without a
 * display: a hidden root holding the project folder and the extra roots, every callback recorded, the answers of the
 * questions set by the test, and a clipboard in memory. The tree items work without the JavaFX toolkit.
 */
internal class FakeEditHost(
    private val project: Path,
    private val extraRoots: List<Path> = emptyList(),
    /** Whether middle folders are compacted (ADR 0096), as the view does by default. */
    private val compact: Boolean = false,
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

    /** Answers the image name question; gets the default name, may change the disk first. */
    var answerImageName: (String) -> String? = { it }
    val imageNameAsks = mutableListOf<NameAsk>()

    /** Answers the text name question with the final name; by default the offered name with the detected extension. */
    var answerTextName: (TextNameAsk) -> String? = { ask -> finalTextName(ask.initialName, ask.detected) }
    val textNameAsks = mutableListOf<TextNameAsk>()

    /** The detector the text paste uses, with the built-in rules only; tests replace it. */
    var textTypes = TextTypeDetector()
    val moved = mutableListOf<Pair<Path, Path>>()
    val deleted = mutableListOf<Pair<Path, List<Path>>>()
    val opened = mutableListOf<Path>()
    val closedUnder = mutableListOf<Path>()
    var vanishedChecks = 0

    /** The explorer search's filter the items list with, as in the view; `null`: no search applied. */
    var search: TreeSearchFilter? = null

    /** The selected items; `select`, `selectRow` and `selectRows` set it, as the view does. */
    var selected: List<TreeItem<Path>> = emptyList()
    val selectRowCalls = mutableListOf<TreeItem<Path>>()
    val selectRowsCalls = mutableListOf<List<TreeItem<Path>>>()

    class NameAsk(
        val title: String,
        val initial: String,
        val validate: (String) -> String?,
        val note: (String) -> String? = { null },
    )

    class TextNameAsk(
        val initialName: String,
        val detected: TextType,
        val folder: Path,
    )

    val host =
        ExplorerEditHost(
            projectItem = { projectItem },
            askName = { title, initial, validate ->
                nameAsks += NameAsk(title, initial, validate)
                answerName(initial)
            },
            askImageName = { initial, validate, note ->
                imageNameAsks += NameAsk("Paste Image", initial, validate, note)
                answerImageName(initial)
            },
            askTextName = { initialName, detected, folder ->
                val ask = TextNameAsk(initialName, detected, folder)
                textNameAsks += ask
                answerTextName(ask)
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
                expandTo(parent, path)?.also(::selectRow)
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
            keepFound = { search?.keep(it) },
        )

    /** The time that names pasted images; fixed. */
    val clock: Clock = Clock.fixed(Instant.parse("2026-03-04T05:06:07Z"), ZoneOffset.UTC)

    val edits = ExplorerEdits(host)
    val moves = ExplorerMoves(host, edits::canChange, edits::isBeingDeleted, edits::canChangePath)

    /** The clipboard the copies use, on [clipboardFiles] instead of the system clipboard. */
    val clipboardFiles = FakeClipboardFiles()
    val clipboard = ExplorerClipboard(clipboardFiles)
    val copies = ExplorerCopies(host, moves, { clipboard }, edits::canChange, edits::isBeingDeleted, clock)

    init {
        reopen()
    }

    /** Opens the folders again as new items; the old ones are no longer shown. */
    fun reopen() {
        projectItem =
            FileTreeItem(project, compact = { compact }, searchFilter = { search }).also { it.isExpanded = true }
        extraItems =
            extraRoots.map {
                FileTreeItem(it, compact = { compact }, searchFilter = { search }).also { item ->
                    item.isExpanded = true
                }
            }
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

    /** The shown row named [name] as shown (`src/main/kotlin` for a compacted one) in [parent], the project by default. */
    fun row(
        name: String,
        parent: TreeItem<Path> = projectItem,
    ): FileTreeItem = parent.children.map { it as FileTreeItem }.first { it.displayName == name }

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

    /** The image on the clipboard; [unreadable] makes it one that cannot be read. */
    var image: ClipboardImage? = null
    var unreadable = false

    /** How often the files and the image were asked for, to see that a menu show reads the clipboard once. */
    var filesReads = 0
    var imageChecks = 0

    override fun hasImage(): Boolean {
        imageChecks++
        return image != null || unreadable
    }

    override fun image(): ClipboardImage? {
        if (unreadable) throw UnreadableClipboardImage()
        return image
    }

    override fun files(): List<Path> {
        filesReads++
        return content.orEmpty()
    }

    override fun put(files: List<Path>) {
        content = files
    }

    override fun clear() {
        content = null
    }
}

/** A file that is created, then fails when written: the disk is full or goes away. */
internal fun failingStream(failure: () -> Throwable): (Path) -> OutputStream =
    { file ->
        val real = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        object : OutputStream() {
            override fun write(b: Int) = throw failure()

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) = throw failure()

            override fun close() = real.close()
        }
    }
