package dev.sluide.fileviewers

import dev.sluide.textediting.DiskChange
import dev.sluide.textediting.LineEnding
import dev.sluide.textediting.TextReplacement
import javafx.beans.binding.Bindings
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
import org.fxmisc.richtext.LineNumberFactory
import org.fxmisc.richtext.util.UndoUtils
import java.nio.file.Files
import java.nio.file.Path

/**
 * Editable view of [file] as code highlighted by [highlighter] (plain text without one), with line numbers. Used by
 * the code viewers, for plain text and as the source side of other views, e.g. the Markdown preview.
 *
 * The area holds the text with `\n` line breaks; [save] writes it with the file's [LineEnding]. The view is [dirty]
 * while its undo history is away from where the file was last read or saved; undo only covers the text, not the
 * highlighting. A file that is not valid UTF-8 is shown read-only, with a banner, and never saved: its invalid bytes
 * show as replacement characters, which saving would write back.
 *
 * When the file changes on disk ([reload]), a view without unsaved changes shows the new text, changing only what
 * differs, so caret, scroll position and undo history stay. A view with unsaved changes keeps them and shows a banner
 * offering to reload the file or to keep its own text, which the next save then writes over the file's; until then the
 * view stays dirty, even when undone back to the text saved last.
 */
internal class CodeView(
    private val file: Path,
    private val highlighter: SyntaxHighlighter?,
) : EditableFileView {
    /** The RichTextFX area showing the text, for views that embed this one. */
    val area =
        CodeArea().apply {
            paragraphGraphicFactory = LineNumberFactory.get(this)
        }

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
            top = banners
            stylesheets += checkNotNull(CodeView::class.java.getResource("code-view.css")).toExternalForm()
        }

    /** The file's text as last read or saved, with `\n` line breaks. */
    private var savedText: String

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
            Button("Reload").apply { setOnAction { conflict?.let(::load) } },
            Button("Keep mine").apply { setOnAction { keepMine() } },
        )

    /** Shown while the file is not valid UTF-8, and so read-only. */
    private val invalidBanner = banner("${file.fileName} is not valid UTF-8; it is shown read-only.")

    init {
        val content = read()
        lineEnding = LineEnding.of(content.raw)
        savedText = LineEnding.normalize(content.raw)
        show(savedText)
        showReadOnly(!content.valid)
        undoManager.forgetHistory()
        undoManager.mark()
        // Following the caret keeps the area at the top when it is first laid out; without it, the source side of the
        // Markdown view (in a SplitPane) opened scrolled down.
        area.moveTo(0)
        area.requestFollowCaret()
    }

    override val node: Node get() = root

    override val dirty: ObservableBooleanValue = Bindings.not(undoManager.atMarkedPositionProperty()).or(keptMine)

    /** Whether the banner about a change on disk that meets unsaved changes is shown. */
    val conflictShown: Boolean get() = conflictBanner in banners.children

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
    }

    override fun reload() {
        val content = read()
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

/** A bar at the top of a view with [message] and [actions]. */
private fun banner(
    message: String,
    vararg actions: Button,
): HBox =
    HBox(Label(message), Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, *actions).apply {
        styleClass += "editor-banner"
        alignment = Pos.CENTER_LEFT
    }
