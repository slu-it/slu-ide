package dev.sluide.findinfiles

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.fileicons.FILE_ICONS_CSS
import dev.sluide.fileicons.fileIconFor
import dev.sluide.fileicons.show
import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.findinfiles.search.FileHits
import dev.sluide.findinfiles.search.FindInFilesSearch
import dev.sluide.findinfiles.search.SearchListener
import dev.sluide.findinfiles.search.SearchOutcome
import dev.sluide.findinfiles.search.SearchRequest
import dev.sluide.findinfiles.search.SearchScheduler
import dev.sluide.findinfiles.search.SearchState
import dev.sluide.findinfiles.search.searchContents
import dev.sluide.findinfiles.search.statusText
import dev.sluide.findinfiles.ui.FindAnchor
import dev.sluide.findinfiles.ui.FindInFilesPopup
import dev.sluide.findinfiles.ui.FindView
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import dev.sluide.textediting.SearchOptions
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.control.MenuItem
import javafx.scene.layout.StackPane
import javafx.stage.Window
import org.kordamp.ikonli.javafx.FontIcon
import java.nio.file.Path
import java.util.concurrent.Executor
import javafx.util.Duration as FxDuration

/** Opens the match at 0-based [line] and [column] (UTF-16 units) of [file]. */
fun interface MatchOpener {
    fun open(
        file: Path,
        line: Int,
        column: Int,
    )
}

/**
 * Find in Files of one window (ADR 0177): the popup, what it remembers (query, options, mask) and the search behind it.
 * FX thread; [close] with the window.
 */
class FindInFiles internal constructor(
    private val fileViewers: FileViewers,
    /** Makes the popup on first use, given this (its previews, highlighter and font). */
    private val view: (FindInFiles) -> FindView,
    /** The editor font as CSS, for the popup's preview and result rows. */
    internal val editorFont: ObservableValue<String>,
    scheduler: SearchScheduler,
    background: Executor,
    previewBackground: Executor,
    runOnFx: (Runnable) -> Unit,
) : CodeEditorExtension,
    AutoCloseable {
    constructor(fileViewers: FileViewers, fontSettings: FontSettings) : this(
        fileViewers,
        { find -> FindInFilesPopup(find::highlighterFor, ::fileIcon, find.previewTexts, find.editorFont) },
        fontSettings.font(FontArea.EDITOR).map(FontSpec::toCss),
        pauseScheduler,
        NamedThreads.single("sluide-find-in-files"),
        NamedThreads.single("sluide-find-in-files-preview"),
        Platform::runLater,
    )

    /** The folders searched; FX thread. */
    var roots: () -> List<Path> = { emptyList() }

    /** What a folder lists (the tree's listing); called off the FX thread. */
    var entries: (Path) -> List<Path> = { emptyList() }

    /** The unsaved editor texts by file; FX thread, at each search start. */
    var unsavedTexts: () -> Map<Path, String> = { emptyMap() }

    /** The opener of a match, asked when the popup opens (it captures where the jump starts); FX thread. */
    var opener: () -> MatchOpener = { MatchOpener { _, _, _ -> } }

    private var query = ""
    private var options = SearchOptions()
    private var mask = ""

    /** The folder searched instead of the roots; set by each opening, cleared by widening. */
    private var scope: Path? = null

    /** Set while the popup's fields are filled from here: that is not an edit. */
    private var filling = false
    private var captured = MatchOpener { _, _, _ -> }
    private val editors = mutableListOf<ExtensibleCodeEditor>()
    private var viewMade = false

    private val popup: FindView by lazy {
        view(this).also {
            viewMade = true
            wire(it)
        }
    }

    private val search =
        FindInFilesSearch(
            scheduler = scheduler,
            background = background,
            runOnFx = runOnFx,
            walk = { request, cancelled, onFile -> searchContents(request, { entries(it) }, cancelled, onFile) },
            listener = Listener(),
        )

    internal val previewTexts = FilePreviewTexts({ unsavedTexts() }, previewBackground, runOnFx)

    /** The highlighter of [file] with [text] for the popup's preview. */
    internal fun highlighterFor(
        file: Path,
        text: String,
    ): SyntaxHighlighter? = fileViewers.highlighterFor(file, text)

    /** Why the popup cannot open ("no folder open"), `null` if it can; for the Navigate menu. */
    fun blocker(): String? = if (roots().isEmpty()) "no folder open" else null

    /**
     * Opens the popup over [window]: for the whole window, or for [folder]. The query is the single-line selection of
     * the focused code editor, else the remembered one. `false` when no folder is open; `true` also when it already
     * shows.
     */
    fun open(
        window: Window,
        folder: Path? = null,
    ): Boolean = show(window, folder) { focusedSelection(window)?.let { selectionQuery(it, options.regex) } }

    private fun show(
        window: Window,
        folder: Path?,
        newQuery: () -> String?,
    ): Boolean {
        if (roots().isEmpty()) return false
        if (popup.isShowing) return true
        val anchor = anchorOf(window) ?: return false
        return openWith(anchor, folder, newQuery())
    }

    /** Opens the popup at [anchor] with [newQuery] (`null`: the remembered one); `true` also when it already shows. */
    internal fun openWith(
        anchor: FindAnchor,
        folder: Path?,
        newQuery: String?,
    ): Boolean {
        if (roots().isEmpty()) return false
        if (popup.isShowing) return true
        captured = opener()
        newQuery?.let { query = it }
        scope = folder
        filling = true
        try {
            popup.query = query
            popup.mask = mask
            popup.options = options
            popup.scopeLabel = folder?.let(::scopeLabel)
        } finally {
            filling = false
        }
        popup.open(anchor)
        search.runNow(searchRequest())
        return true
    }

    private fun scopeLabel(folder: Path): String {
        val root = roots().firstOrNull { folder.startsWith(it) }
        val relative = root?.relativize(folder)?.toString().orEmpty()
        return relative.ifEmpty { folder.fileName?.toString() ?: folder.toString() }
    }

    /** The request for the popup's state now; also tells the view whether its headers need the root's name. */
    private fun searchRequest(): SearchRequest {
        val all = roots()
        popup.severalRoots = scope == null && all.size > 1
        return request(all)
    }

    private fun request(allRoots: List<Path>) =
        SearchRequest(
            roots = allRoots,
            scope = scope,
            query = query,
            options = options,
            mask = mask,
            unsaved = unsavedTexts().mapKeys { it.key.toAbsolutePath().normalize() },
        )

    private fun wire(popup: FindView) {
        popup.onChanged = {
            if (!filling) {
                query = popup.query
                options = popup.options
                mask = popup.mask
                search.changed(searchRequest())
            }
        }
        popup.onWiden = {
            scope = null
            popup.scopeLabel = null
            search.runNow(searchRequest())
        }
        popup.onOpen = { files, line ->
            popup.hideForOpen()
            captured.open(files.file, line.line, line.column)
        }
        popup.onHidden = { search.cancel() }
    }

    private inner class Listener : SearchListener {
        override fun cleared() {
            popup.clear()
            popup.status("")
        }

        override fun started() {
            popup.clear()
            popup.status(statusText(SearchState.Running(0, 0)))
        }

        override fun added(
            files: List<FileHits>,
            status: String,
        ) {
            popup.add(files)
            popup.status(status)
        }

        override fun finished(
            outcome: SearchOutcome,
            status: String,
        ) = popup.status(status)

        override fun failed() = popup.status(statusText(SearchState.Failed))
    }

    /** Adds "Find Selection in Files" to the editor's context menu (ADR 0108). */
    override fun extend(editor: ExtensibleCodeEditor): AutoCloseable? {
        editors += editor
        editor.addContextMenuItems { target ->
            val text = target.selectedText
            when {
                text.isEmpty() -> {
                    emptyList()
                }

                isMultiLine(text) -> {
                    listOf(disabled("Find Selection in Files (more than one line)"))
                }

                blocker() != null -> {
                    listOf(disabled("Find Selection in Files (no folder open)"))
                }

                selectionQuery(text, false) == null -> {
                    listOf(disabled("Find Selection in Files (longer than $MAX_SELECTION_QUERY characters)"))
                }

                else -> {
                    listOf(
                        MenuItem("Find Selection in Files").apply { setOnAction { findSelection(editor, text) } },
                    )
                }
            }
        }
        return AutoCloseable { editors -= editor }
    }

    private fun disabled(label: String) = MenuItem(label).apply { isDisable = true }

    private fun findSelection(
        editor: ExtensibleCodeEditor,
        text: String,
    ) {
        val window = editor.popupOwner?.scene?.window ?: return
        show(window, null) { selectionQuery(text, options.regex) }
    }

    /** The selection of the editor of [window] that has the focus. */
    private fun focusedSelection(window: Window): String? {
        val focus = window.scene?.focusOwner ?: return null
        val editor = editors.firstOrNull { editor -> editor.popupOwner?.let { holdsFocus(it, focus) } == true }
        val range = editor?.selection ?: return null
        if (range.length == 0 || range.length > MAX_SELECTION_QUERY) return null
        return editor.text.substring(range.start, range.end)
    }

    override fun close() {
        if (viewMade && popup.isShowing) popup.hide()
        search.cancel()
        editors.clear()
    }

    private companion object {
        val pauseScheduler =
            SearchScheduler { wait, task ->
                val pause = PauseTransition(FxDuration.millis(wait.toMillis().toDouble()))
                pause.setOnFinished { task.run() }
                pause.play()
                AutoCloseable { pause.stop() }
            }
    }
}

/** Whether [focus] is [owner] or inside it. */
private fun holdsFocus(
    owner: Node,
    focus: Node,
): Boolean = generateSequence(focus) { it.parent }.any { it === owner }

/** The popup's place: the content of [window]; its focus owner gets the focus back. */
private fun anchorOf(window: Window): FindAnchor? {
    val scene = window.scene ?: return null
    val root = scene.root ?: return null
    val bounds = root.localToScreen(root.boundsInLocal) ?: return null
    return FindAnchor(window, bounds, scene.focusOwner)
}

/**
 * The file-type icon of a result's [file] (in a pane carrying the icons' colours, as a `FontIcon` takes no
 * stylesheet); files with matches are text, so an unknown type shows as text.
 */
private fun fileIcon(file: Path): Node =
    StackPane(FontIcon().apply { show(fileIconFor(file, directory = false, expanded = false) { true }) }).apply {
        stylesheets += FILE_ICONS_CSS
    }
