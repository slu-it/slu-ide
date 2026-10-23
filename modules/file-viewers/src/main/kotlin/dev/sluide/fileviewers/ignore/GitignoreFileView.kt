package dev.sluide.fileviewers.ignore

import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.GutterColumns
import dev.sluide.fileviewers.IgnoreFileEffects
import dev.sluide.fileviewers.IgnoreRuleEffects
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TextDiff
import dev.sluide.fileviewers.TextEditableView
import dev.sluide.fileviewers.code.CodeView
import dev.sluide.fileviewers.decodeUtf8
import dev.sluide.textediting.CommentSyntax
import dev.sluide.textediting.IndentStyle
import dev.sluide.textediting.LineEnding
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import java.io.IOException
import java.nio.file.Files
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
    val source = CodeView(file, highlighter, comment, defaultIndent, dispatch)

    private var file = file
    private var service: IgnoreRuleEffects? = null
    private var subscription: AutoCloseable? = null
    private var disposed = false
    private var request = 0

    /** The effects last answered and the saved text they describe. */
    private var known = false
    private var effectsBase = ""
    private var baseDots: Map<Int, IgnoreDot> = emptyMap()
    private var dots: Map<Int, IgnoreDot> = emptyMap()

    init {
        source.addGutterColumn(::column)
        source.savedTextProperty.addListener { _, _, _ -> lookUp() }
        source.area.textProperty().addListener { _, _, _ -> recompute() }
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
            connect()
            lookUp()
        }

    /** Takes the window's service of the host, if any, and listens to it; the one before is let go. */
    private fun connect() {
        subscription?.close()
        subscription = null
        service = host?.windowService(IgnoreRuleEffects::class.java)
        subscription = service?.onChanged(::lookUp)
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
        file = to
        if (to.fileName.toString() != GITIGNORE) {
            subscription?.close()
            subscription = null
            service = null
        } else if (service == null) {
            connect()
        }
        lookUp()
        return true
    }

    override fun dispose() {
        disposed = true
        request++
        subscription?.close()
        subscription = null
        source.dispose()
    }

    /** Asks the service for the effects of the saved file; answers of older or disposed requests are dropped. */
    private fun lookUp() {
        val service = service
        if (disposed) return
        val id = ++request
        if (service == null) {
            apply(null, "")
            return
        }
        val base = source.savedText
        service.lookup(file) { answer ->
            if (!disposed && id == request) apply(answer?.takeIf { onDisk(base) }, base)
        }
    }

    /** Whether the file on disk has the text [saved]; not while the view shows another (a banner offers to reload). */
    private fun onDisk(saved: String): Boolean =
        try {
            val bytes = Files.readAllBytes(file)
            LineEnding.normalize(decodeUtf8(bytes) ?: String(bytes, Charsets.UTF_8)) == saved
        } catch (_: IOException) {
            false
        }

    /** Takes the [answer] for the saved text [base]; `null` means unknown: no dots. */
    private fun apply(
        answer: IgnoreFileEffects?,
        base: String,
    ) {
        val wasKnown = known
        known = answer != null
        effectsBase = base
        baseDots = if (answer == null) emptyMap() else IgnoreRuleDots.of(base, answer)
        recompute(force = wasKnown != known)
    }

    /** Shifts the dots to the text; rebuilds the gutter if they or [force] (the column appears or goes) say so. */
    private fun recompute(force: Boolean = false) {
        val new = IgnoreRuleDots.edited(effectsBase, source.text, baseDots)
        if (new == dots && !force) return
        dots = new
        source.refreshGutter()
    }

    /** The dot of a line; a blank cell of the same width for other lines while the effects are known, so the text stays aligned. */
    private fun column(paragraph: Int): Node? {
        if (!known) return null
        val dot = dots[paragraph] ?: return cell()
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
        const val GITIGNORE = ".gitignore"
        const val MARK = 7.0
        const val CELL = 14.0
    }
}
