package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import dev.sluide.textediting.DiskChange
import dev.sluide.textediting.IndentStyle
import dev.sluide.textediting.LineEnding
import dev.sluide.textediting.TextReplacement
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.model.PlainTextChange
import org.fxmisc.richtext.model.TwoDimensional
import org.fxmisc.richtext.util.UndoUtils
import org.fxmisc.wellbehaved.event.Nodes
import org.reactfx.Subscription
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Editable view of [file] as code highlighted by [highlighter] (plain text without one), with line numbers and
 * markers of the lines changed against an original ([showChanges]) in its [Gutter]. Used by the code viewers, for
 * plain text and as the source side of other views, e.g. the Markdown preview.
 *
 * The area holds the text with `\n` line breaks; [save] writes it with the file's [LineEnding]. The view is [dirty]
 * while its undo history is away from where the file was last read or saved; undo only covers the text, not the
 * highlighting. A file that is not valid UTF-8 is shown read-only, with a banner, and never saved: its invalid bytes
 * show as replacement characters, which saving would write back.
 *
 * Edited text is highlighted again once typing pauses, on a background thread; the result is handed to the FX thread
 * through [dispatch] (`Platform.runLater`, replaced in tests), and dropped if the text changed meanwhile. Only the
 * range whose styles changed is restyled.
 *
 * When the file changes on disk ([reload]), a view without unsaved changes shows the new text, changing only what
 * differs, so caret, scroll position and undo history stay. A view with unsaved changes keeps them and shows a banner
 * offering to reload the file or to keep its own text, which the next save then writes over the file's; until then the
 * view stays dirty, even when undone back to the text saved last. When the file is deleted, a banner says so; saving
 * creates it again.
 *
 * Keys for editing ([editorKeys]) indent in the file's [indentStyle] and comment out lines with [comment].
 */
internal class CodeView(
    private val file: Path,
    private val highlighter: SyntaxHighlighter?,
    comment: CommentSyntax = CommentSyntax.NONE,
    private val dispatch: (Runnable) -> Unit = Platform::runLater,
) : EditableFileView,
    GutterColumns {
    /** The RichTextFX area showing the text, for views that embed this one. */
    val area = CodeArea()

    /** Line numbers, change markers ([showChanges]) and further columns ([addGutterColumn]). */
    private val gutter = Gutter(area) { line -> host?.showChanges(file, line) }

    /** Shows the changes when a change marker is clicked. */
    override var host: FileViewHost? = null

    // The default undo manager would also undo highlighting.
    private val undoManager = UndoUtils.plainTextUndoManager(area).also { area.undoManager = it }
    private val pane =
        VirtualizedScrollPane(area).apply {
            styleClass += "code-view"
            stylesheets += checkNotNull(CodeView::class.java.getResource("syntax.css")).toExternalForm()
        }

    /** Banners above the text, e.g. [conflictBanner]. */
    private val banners = VBox()
    private val root =
        BorderPane(pane).apply {
            // Unlike `code-view`, which the scroll panes of diff views have too.
            styleClass += "code-editor"
            top = banners
            stylesheets += checkNotNull(CodeView::class.java.getResource("code-view.css")).toExternalForm()
        }

    private val savedTextWrapper = ReadOnlyStringWrapper()

    /** The file's text as last read or saved, with `\n` line breaks; also what the user kept over a change on disk. */
    val savedTextProperty: ReadOnlyStringProperty = savedTextWrapper.readOnlyProperty

    /** The current value of [savedTextProperty]. */
    var savedText: String
        get() = savedTextWrapper.get()
        private set(value) = savedTextWrapper.set(value)

    /** The line ending the file is saved with. */
    private var lineEnding: LineEnding

    /** The file's content on disk when it last changed while the view had unsaved changes. */
    private var conflict: FileContent? = null

    /** Whether the user kept the unsaved text over a change on disk ([keepMine]), which only a save writes. */
    private val keptMine = SimpleBooleanProperty(false)

    /** Shown while the file changed on disk and the view has unsaved changes. */
    private val conflictBanner =
        banner(
            "${file.fileName} changed on disk.",
            Button("Reload").apply { setOnAction { answered { conflict?.let(::load) } } },
            Button("Keep mine").apply { setOnAction { answered(::keepMine) } },
        )

    /** Shown while the file is not valid UTF-8, and so read-only. */
    private val invalidBanner = banner("${file.fileName} is not valid UTF-8; it is shown read-only.")

    /** Runs a banner's [action]; the focus goes back to the text, rather than wherever the vanished button sends it. */
    private fun answered(action: () -> Unit) {
        action()
        // The file's text is the saved text now, which a diff given meanwhile is of.
        pendingChanges?.let { showChanges(it.diff) }
        area.requestFocus()
    }

    /** Counts the changes of the text, so that highlighting done for an older text is dropped. */
    private var revision = 0L

    /** The revision highlighting was asked for last; the worker skips older requests still waiting for it. */
    @Volatile private var requested = 0L
    private var disposed = false
    private val highlighting: Subscription

    /** The change markers shown in the [gutter], following unsaved edits. */
    private val changeMarkers: ChangeMarkers

    /** Shown while the file does not exist, e.g. after it was deleted while the view had unsaved changes. */
    private val deletedBanner = banner("${file.fileName} was deleted on disk; saving recreates it.")

    /** How the editing keys indent, from `.editorconfig` or the text as opened. */
    val indentStyle: IndentStyle

    init {
        val content = read()
        lineEnding = LineEnding.of(content.raw)
        savedText = LineEnding.normalize(content.raw)
        show(savedText)
        showReadOnly(!content.valid)
        undoManager.forgetHistory()
        undoManager.mark()
        indentStyle = IndentStyle.forFile(file, savedText)
        Nodes.addInputMap(area, editorKeys(area, indentStyle, comment))
        // Following the caret keeps the area at the top when it is first laid out; without it, the source side of the
        // Markdown view (in a SplitPane) opened scrolled down.
        area.moveTo(0)
        area.requestFollowCaret()
        val pause = Duration.ofMillis(if (savedText.length > LARGE_TEXT) 600 else 150)
        highlighting =
            Subscription.multi(
                area.plainTextChanges().subscribe { revision++ },
                area.multiPlainChanges().successionEnds(pause).subscribe { highlightInBackground() },
            )
        changeMarkers = ChangeMarkers(savedText)
        area.multiPlainChanges().subscribe(::moveChangeMarkers)
        // Undoing reaches the saved text only after the undone change was reported.
        undoManager.atMarkedPositionProperty().addListener { _, _, atMark -> if (atMark) resetChangeMarkers() }
        savedTextProperty.addListener { _, _, saved -> changeMarkers.saved(saved, area.text) }
    }

    override val node: Node get() = root

    override val dirty: ObservableBooleanValue = Bindings.not(undoManager.atMarkedPositionProperty()).or(keptMine)

    /** Whether the banner about a change on disk that meets unsaved changes is shown. */
    val conflictShown: Boolean get() = conflictBanner in banners.children

    /** Whether the banner about the file being deleted is shown. */
    val deletedShown: Boolean get() = deletedBanner in banners.children

    /** Whether the file is not valid UTF-8 and therefore shown read-only. */
    val readOnly: Boolean get() = invalidBanner in banners.children

    /** Writes the text to the file; does nothing while the view is [readOnly]. */
    override fun save() {
        if (readOnly) return
        val text = area.text
        // In place, so the file keeps its permissions and symbolic links stay links.
        Files.writeString(file, lineEnding.restore(text))
        savedText = text
        keptMine.set(false)
        undoManager.mark()
        // Typing on would otherwise extend the change before the mark.
        undoManager.preventMerge()
        hideConflict()
        banners.children -= deletedBanner
    }

    /**
     * The diff's markers apply to the saved text; unsaved edits move them along ([moveChangeMarkers]). While the file
     * changed on disk and the view keeps its unsaved changes, the diff (of the file's new text) waits until the banner
     * is answered, which makes that text the saved one.
     */
    override fun showChanges(diff: TextDiff?) {
        if (conflictShown) {
            pendingChanges = PendingChanges(diff)
            return
        }
        pendingChanges = null
        changeMarkers.show(diff, savedText, area.text)
        gutter.markers = changeMarkers.markers
        // Without a diff, the markers show unsaved edits only, and there is no diff to open.
        gutter.markersClickable = diff != null
    }

    /** The diff given to [showChanges] while the conflict banner was shown. */
    private var pendingChanges: PendingChanges? = null

    private class PendingChanges(
        val diff: TextDiff?,
    )

    /**
     * Moves the change markers along with [changes]: replaced lines become modified, inserted ones added. Of several
     * changes at once, each is seen in the text as it was before it. Back at the saved text (e.g. after undoing), the
     * markers are the diff's again.
     */
    private fun moveChangeMarkers(changes: List<PlainTextChange>) {
        val change = changes.singleOrNull()
        if (change == null) {
            // Each change's position is in the text after the ones before it: undo them all, then redo one by one.
            var text = area.text
            for (c in changes.asReversed()) {
                text =
                    text.replaceRange(c.position, c.position + c.inserted.length, c.removed)
            }
            for (c in changes) {
                changeMarkers.edited(LineEdit.at(text, c.position, c.removed, c.inserted))
                text = text.replaceRange(c.position, c.position + c.removed.length, c.inserted)
            }
        } else {
            val start = change.position
            // The text before the change and after it is the same as before.
            val end = start + change.inserted.length
            changeMarkers.edited(
                LineEdit.of(
                    line = area.offsetToPosition(start, TwoDimensional.Bias.Forward).major,
                    startsLine = start == 0 || area.getText(start - 1, start) == "\n",
                    endsLine = end == area.length || area.getText(end, end + 1) == "\n",
                    removed = change.removed,
                    inserted = change.inserted,
                ),
            )
        }
        if (undoManager.isAtMarkedPosition) changeMarkers.resetIfUnedited(area.text)
        gutter.markers = changeMarkers.markers
    }

    private fun resetChangeMarkers() {
        changeMarkers.resetIfUnedited(area.text)
        gutter.markers = changeMarkers.markers
    }

    override fun addGutterColumn(column: (paragraph: Int) -> Node?) = gutter.addColumn(column)

    override fun refreshGutter() = gutter.refresh()

    override fun reload() {
        val content =
            try {
                read()
            } catch (_: NoSuchFileException) {
                hideConflict()
                if (!deletedShown) banners.children += deletedBanner
                return
            }
        banners.children -= deletedBanner
        when (DiskChange.decide(content.raw, savedText, area.text, dirty.get())) {
            DiskChange.IGNORE -> {
                adoptLineEnding(content.raw)
                hideConflict()
            }

            DiskChange.RELOAD -> {
                load(content)
            }

            DiskChange.CONFLICT -> {
                conflict = content
                if (!conflictShown) banners.children += conflictBanner
            }
        }
    }

    /** The file's content: decoded strictly, or with replacement characters if it is not valid UTF-8. */
    private fun read(): FileContent {
        val bytes = Files.readAllBytes(file)
        val text = decodeUtf8(bytes)
        return FileContent(text ?: String(bytes, Charsets.UTF_8), valid = text != null)
    }

    /** Shows [content] as the saved text, keeping caret, scroll position and undo history. */
    private fun load(content: FileContent) {
        val text = LineEnding.normalize(content.raw)
        hideConflict()
        adoptLineEnding(content.raw)
        showReadOnly(!content.valid)
        val replacement = TextReplacement.between(area.text, text)
        if (!replacement.isEmpty) {
            val caret = replacement.map(area.caretPosition)
            val scrollX = pane.estimatedScrollX
            val scrollY = pane.estimatedScrollY
            // The reload is an undo step of its own.
            undoManager.preventMerge()
            area.replaceText(replacement.start, replacement.end, replacement.replacement)
            highlight(text)
            area.moveTo(caret)
            // Moving the caret makes the area follow it during the next layout; lay out now, then scroll back.
            pane.layout()
            pane.scrollXToPixel(scrollX)
            pane.scrollYToPixel(scrollY)
        }
        savedText = text
        keptMine.set(false)
        undoManager.mark()
        undoManager.preventMerge()
    }

    /** Keeps the unsaved text over the file's new one, which only counts as saved, so the next save overwrites it. */
    private fun keepMine() {
        conflict?.let { savedText = LineEnding.normalize(it.raw) }
        keptMine.set(true)
        hideConflict()
    }

    private fun hideConflict() {
        conflict = null
        banners.children -= conflictBanner
    }

    private fun showReadOnly(readOnly: Boolean) {
        area.isEditable = !readOnly
        if (!readOnly) {
            banners.children -= invalidBanner
        } else if (invalidBanner !in banners.children) {
            banners.children += invalidBanner
        }
    }

    /** Takes the line ending of [raw], the file's content, unless it has no line break to tell. */
    private fun adoptLineEnding(raw: String) {
        if (raw.any { it == '\n' || it == '\r' }) lineEnding = LineEnding.of(raw)
    }

    override fun dispose() {
        disposed = true
        highlighting.unsubscribe()
    }

    override fun focusText() = area.requestFocus()

    private fun highlightInBackground() {
        val highlighter = highlighter ?: return
        val text = area.text
        if (text.isEmpty()) return
        val highlighted = revision
        requested = highlighted
        highlightExecutor.execute {
            if (requested != highlighted) return@execute
            val spans = codeStyleSpans(text, highlighter)
            dispatch(
                Runnable {
                    if (disposed || revision != highlighted) return@Runnable
                    val range = changedSpanRange(area.getStyleSpans(0, area.length), spans) ?: return@Runnable
                    area.setStyleSpans(range.first, spans.subView(range.first, range.last + 1))
                },
            )
        }
    }

    private fun show(text: String) {
        area.replaceText(text)
        highlight(text)
    }

    private fun highlight(text: String) {
        if (text.isNotEmpty() && highlighter != null) area.setStyleSpans(0, codeStyleSpans(text, highlighter))
    }
}

/** A file's content as read, [raw] with its line endings; not [valid] UTF-8 if it shows replacement characters. */
private class FileContent(
    val raw: String,
    val valid: Boolean,
)

/** Texts above this length (about 1 MiB) are highlighted after a longer pause in typing. */
private const val LARGE_TEXT = 1 shl 20

/** Highlights edited code of all views, one text at a time. */
private val highlightExecutor =
    Executors.newSingleThreadExecutor { Thread(it, "sluide-highlight").apply { isDaemon = true } }

/** A bar at the top of a view with [message] and [actions]. */
private fun banner(
    message: String,
    vararg actions: Button,
): HBox =
    HBox(Label(message), Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, *actions).apply {
        styleClass += "editor-banner"
        alignment = Pos.CENTER_LEFT
    }
