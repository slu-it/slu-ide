package dev.sluide.fileviewers.env

import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.GutterColumns
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TextDiff
import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.code.CodeView
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import dev.sluide.textediting.IndentStyle
import javafx.application.Platform
import javafx.beans.property.BooleanProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import org.fxmisc.richtext.model.TwoDimensional
import org.reactfx.Subscription
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Shows `.env` files (`.env`, `.env.*`, `*.env`) highlighted, with their values masked ([EnvFileView]). Being a
 * [CodeFileViewer], its files get change markers and highlighted diffs like code; the diff shows the values.
 */
@Component
class EnvFileViewer :
    CodeFileViewer(
        setOf("env"),
        RSyntaxHighlighter.env,
        CommentSyntax.HASH,
        namePatterns = listOf(Regex("""\.env\..+""")),
    ) {
    override fun open(file: Path): FileView = EnvFileView(file, highlighter, comment, defaultIndent)
}

/**
 * A `.env` file in a [CodeView] whose values ([EnvValues]) are masked: styled `masked`, which hides their text behind
 * a solid bar, while the text itself stays as it is, so copying, finding and saving use the real values. The toolbar's
 * **Show values** ([showValues]) shows them all; it is off whenever a view opens. Revealed are the line with the caret
 * while the text has the focus, once the caret was placed (moved or typed at, so not the first line right after
 * opening), so that editing is never blind; and the lines of a find match the find bar selected. Every change of the
 * text is masked right away, not only once highlighting catches up. Placeholder files
 * ([EnvValues.masks], e.g. `.env.example`) are not masked and have no toolbar.
 */
internal class EnvFileView(
    file: Path,
    highlighter: SyntaxHighlighter,
    comment: CommentSyntax,
    defaultIndent: IndentStyle,
    dispatch: (Runnable) -> Unit = Platform::runLater,
) : EditableFileView,
    GutterColumns {
    /** The text; [dispatch] hands it the background highlighting, as for [CodeView]. */
    val source = CodeView(file, highlighter, comment, defaultIndent, dispatch = dispatch)

    /** Whether all values are shown. */
    val showValues: BooleanProperty = SimpleBooleanProperty(false)

    /** Whether the file's values are masked at all; not in placeholder files. */
    var masks = EnvValues.masks(file.fileName.toString())
        private set

    private val toolbar =
        HBox(
            ToggleButton("Show values").apply {
                styleClass += "env-show-values"
                tooltip = Tooltip("Show the values of all lines, not only the one with the caret")
                selectedProperty().bindBidirectional(showValues)
            },
        ).apply {
            styleClass += "preview-toolbar"
            alignment = Pos.CENTER_RIGHT
        }

    private val pane =
        BorderPane(source.node).apply {
            stylesheets +=
                checkNotNull(
                    EnvFileView::class.java.getResource("/dev/sluide/fileviewers/view-toolbar.css"),
                ).toExternalForm()
        }

    /** The lines (0-based) whose values are shown although masked. */
    private var revealed: Set<Int> = emptySet()

    /** Whether the caret moved since the view opened. */
    private var caretPlaced = false

    /** Masks edited text anew, synchronously with every change of the text. */
    private val changes: Subscription

    init {
        val area = source.area
        source.maskedRanges = { text ->
            if (!masks || showValues.get()) emptyList() else EnvValues.ranges(text) { it in revealed }
        }
        area.caretPositionProperty().addListener { _, _, _ ->
            caretPlaced = true
            reveal()
        }
        area.selectionProperty().addListener { _, _, _ -> reveal() }
        // Every edit, right away: inserted text takes the styles around it (an undone Replace All brings the old
        // values back unmasked), and lines shift under the revealed line numbers (a deleted line).
        changes =
            area.multiPlainChanges().subscribe {
                revealed = revealedLines()
                source.restyleMasks()
            }
        // The focus within the window: masking again when the app loses the focus would add nothing.
        area.sceneProperty().flatMap { it.focusOwnerProperty() }.addListener { _, _, _ -> reveal() }
        showValues.addListener { _, _, _ -> source.restyleMasks() }
        showToolbar()
        source.restyleMasks()
    }

    override val node: Node get() = pane

    override val dirty get() = source.dirty

    override var host: FileViewHost?
        get() = source.host
        set(value) {
            source.host = value
        }

    override fun save() = source.save()

    override fun reload() = source.reload()

    override fun showChanges(diff: TextDiff?) = source.showChanges(diff)

    override fun focusText() = source.focusText()

    override fun focus() = source.focus()

    override fun dispose() {
        changes.unsubscribe()
        source.dispose()
    }

    override fun addGutterColumn(
        width: Double,
        column: (paragraph: Int) -> Node?,
    ) = source.addGutterColumn(width, column)

    override fun refreshGutter() = source.refreshGutter()

    /** Follows the file; renamed to or from a placeholder file, its values are masked or shown from now on. */
    override fun moved(to: Path): Boolean {
        source.moved(to)
        masks = EnvValues.masks(to.fileName.toString())
        showToolbar()
        source.restyleMasks()
        return true
    }

    private fun showToolbar() {
        pane.top = if (masks) toolbar else null
    }

    /** Masks and reveals the lines anew if the [revealedLines] changed. */
    private fun reveal() {
        val lines = revealedLines()
        if (lines == revealed) return
        revealed = lines
        source.restyleMasks()
    }

    private fun revealedLines(): Set<Int> {
        val area = source.area
        val lines = HashSet<Int>()
        if (caretPlaced && area.scene?.focusOwner === area) lines += area.currentParagraph
        val selection = area.selection
        if (selection.length > 0 && source.findBar.isMatch(selection)) {
            val first = area.offsetToPosition(selection.start, TwoDimensional.Bias.Forward).major
            val last = area.offsetToPosition(selection.end, TwoDimensional.Bias.Backward).major
            lines.addAll(first..last)
        }
        return lines
    }
}
