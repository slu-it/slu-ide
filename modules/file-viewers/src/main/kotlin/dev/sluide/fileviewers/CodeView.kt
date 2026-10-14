package dev.sluide.fileviewers

import dev.sluide.textediting.LineEnding
import javafx.beans.binding.Bindings
import javafx.beans.value.ObservableBooleanValue
import javafx.scene.Node
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.LineNumberFactory
import org.fxmisc.richtext.util.UndoUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Editable view of [file] as code highlighted by [highlighter] (plain text without one), with line numbers. Used by
 * the code viewers, for plain text and as the source side of other views, e.g. the Markdown preview.
 *
 * The area holds the text with `\n` line breaks; [save] writes it with the file's [LineEnding]. The view is [dirty]
 * while its undo history is away from where the file was last read or saved; undo only covers the text, not the
 * highlighting.
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

    /** The file's text as last read or saved, with `\n` line breaks. */
    private var savedText: String

    /** The line ending the file is saved with. */
    private var lineEnding: LineEnding

    init {
        val raw = file.readText()
        lineEnding = LineEnding.of(raw)
        savedText = LineEnding.normalize(raw)
        show(savedText)
        undoManager.forgetHistory()
        undoManager.mark()
        // Following the caret keeps the area at the top when it is first laid out; without it, the source side of the
        // Markdown view (in a SplitPane) opened scrolled down.
        area.moveTo(0)
        area.requestFollowCaret()
    }

    override val node: Node get() = pane

    override val dirty: ObservableBooleanValue = Bindings.not(undoManager.atMarkedPositionProperty())

    override fun save() {
        val text = area.text
        // In place, so the file keeps its permissions and symbolic links stay links.
        Files.writeString(file, lineEnding.restore(text))
        savedText = text
        undoManager.mark()
        // Typing on would otherwise extend the change before the mark.
        undoManager.preventMerge()
    }

    override fun reload() {
        val raw = file.readText()
        val text = LineEnding.normalize(raw)
        if (text == area.text) return
        val caret = area.caretPosition
        val scrollX = pane.estimatedScrollX
        val scrollY = pane.estimatedScrollY
        show(text)
        lineEnding = LineEnding.of(raw)
        savedText = text
        undoManager.mark()
        area.moveTo(caret.coerceAtMost(text.length))
        // Moving the caret makes the area follow it during the next layout; lay out now, then scroll back.
        pane.layout()
        pane.scrollXToPixel(scrollX)
        pane.scrollYToPixel(scrollY)
    }

    private fun show(text: String) {
        area.replaceText(text)
        if (text.isNotEmpty() && highlighter != null) area.setStyleSpans(0, codeStyleSpans(text, highlighter))
    }
}
