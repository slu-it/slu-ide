package dev.sluide.fileviewers.ignore

import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.GutterColumns
import dev.sluide.fileviewers.IgnoreRuleEffects
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TextDiff
import dev.sluide.fileviewers.TextEditableView
import dev.sluide.fileviewers.code.CodeView
import dev.sluide.textediting.CommentSyntax
import dev.sluide.textediting.IndentStyle
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import java.nio.file.Path

/**
 * A `.gitignore` in a [CodeView] with a dot beside each pattern line that tells whether the line has an effect
 * ([IgnoreRuleDots], ADR 0080), from the window's [IgnoreRuleEffects]; without that service, or while git does not
 * know, there are no dots. The text is unchanged. The effects describe the saved text; while edited, the dots of
 * unchanged lines move along and edited or new lines have none until the next save.
 */
internal class GitignoreFileView(
    file: Path,
    highlighter: SyntaxHighlighter,
    comment: CommentSyntax,
    defaultIndent: IndentStyle,
    dispatch: (Runnable) -> Unit = Platform::runLater,
) : EditableFileView,
    TextEditableView,
    GutterColumns {
    /** The text. */
    val source = CodeView(file, highlighter, comment, defaultIndent, dispatch = dispatch)

    private var disposed = false

    private val tracker =
        IgnoreDotsTracker(
            file,
            savedText = { source.savedText },
            text = { source.text },
            serviceOf = { host?.windowService(IgnoreRuleEffects::class.java) },
            onChanged = { source.refreshGutter() },
        )

    init {
        source.addGutterColumn(::column)
        source.savedTextProperty.addListener { _, _, _ -> tracker.lookUp() }
        source.area.textProperty().addListener { _, _, _ -> tracker.recompute() }
    }

    override val node: Node get() = source.node

    override val dirty get() = source.dirty

    override val text: String get() = source.text

    override val canReplaceText: Boolean get() = source.canReplaceText

    override var host: FileViewHost?
        get() = source.host
        set(value) {
            source.host = value
            if (disposed) return
            tracker.connect()
            tracker.lookUp()
        }

    override fun save() = source.save()

    override fun reload() = source.reload()

    override fun showChanges(diff: TextDiff?) = source.showChanges(diff)

    override fun replaceText(text: String) = source.replaceText(text)

    override fun showLine(line: Int) = source.showLine(line)

    override fun focusText() = source.focusText()

    override fun focus() = source.focus()

    override fun addGutterColumn(column: (paragraph: Int) -> Node?) = source.addGutterColumn(column)

    override fun refreshGutter() = source.refreshGutter()

    /** Follows the file; renamed to another name than `.gitignore`, the view has no dots any more. */
    override fun moved(to: Path): Boolean {
        if (!source.moved(to)) return false
        tracker.moved(to)
        return true
    }

    override fun dispose() {
        disposed = true
        tracker.dispose()
        source.dispose()
    }

    /** The dot of a line; a blank cell of the same width for other lines while the effects are known, so the text stays aligned. */
    private fun column(paragraph: Int): Node? {
        if (!tracker.isKnown) return null
        val dot = tracker.dotOf(paragraph) ?: return cell()
        return cell().apply {
            styleClass += "ignore-rule-dot"
            styleClass += dot.kind.styleClass
            children +=
                Region().apply {
                    styleClass += "ignore-rule-mark"
                    styleClass += dot.kind.styleClass
                    setMinSize(MARK, MARK)
                    setPrefSize(MARK, MARK)
                    setMaxSize(MARK, MARK)
                }
            Tooltip.install(this, Tooltip(dot.tooltip))
            // Not pressed into the text: the caret stays where it is.
            addEventHandler(MouseEvent.MOUSE_PRESSED) { it.consume() }
        }
    }

    private fun cell() =
        StackPane().apply {
            setMinWidth(CELL)
            setPrefWidth(CELL)
            setMaxWidth(CELL)
            maxHeight = Double.MAX_VALUE
        }

    private companion object {
        const val MARK = 7.0
        const val CELL = 14.0
    }
}
