package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.GutterColumns
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.TextChange
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.fileviewers.onShownParagraphsChanged
import dev.sluide.textediting.LineEnding
import dev.sluide.textediting.Selection
import dev.sluide.textediting.TextEdit
import dev.sluide.textediting.TextPosition
import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.ReadOnlyIntegerWrapper
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableBooleanValue
import javafx.beans.value.ObservableValue
import javafx.event.Event
import javafx.event.EventHandler
import javafx.event.EventType
import javafx.geometry.Bounds
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.control.IndexRange
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.util.Subscription
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.model.TwoDimensional.Bias
import java.nio.file.Path

private val log = System.getLogger(ExtensibleCodeView::class.java.name)

/**
 * [ExtensibleCodeEditor] over [view]'s [area] showing [initialFile]; its gutter is the view's, what extensions add
 * beside it goes to [additions]. [plainText]: the view has no highlighter. FX thread.
 */
internal class ExtensibleCodeView(
    private val view: CodeView,
    private val area: CodeArea,
    initialFile: Path,
    private val additions: CodeViewAdditions,
    override val plainText: Boolean,
) : ExtensibleCodeEditor,
    GutterColumns by view {
    private val fileWrapper = ReadOnlyObjectWrapper(initialFile)
    private val menuItems = mutableListOf<(EditorMenuTarget) -> List<MenuItem>>()
    private val savedListeners = mutableListOf<() -> Unit>()
    private var attachedHost: FileViewHost? = null

    /** The cursor over the text once an extension set one; bound, so that the area's stylesheet cannot reset it. */
    private val textCursor = SimpleObjectProperty(Cursor.TEXT)

    private val caretOffsetWrapper =
        ReadOnlyIntegerWrapper(area.caretPosition).also { offset ->
            area.caretPositionProperty().addListener { _, _, caret -> offset.set(caret) }
        }

    /**
     * Whether a key binding handled the last key press, so that the key typed it makes is consumed too: RichTextFX
     * types characters with Alt held (outside Windows), e.g. Alt+J or an Option chord on macOS.
     */
    private var typedHandled = false

    init {
        // Before any binding's filter: a new press starts unhandled.
        area.addEventFilter(KeyEvent.KEY_PRESSED) { typedHandled = false }
        area.addEventFilter(KeyEvent.KEY_TYPED) { event ->
            if (typedHandled) {
                typedHandled = false
                event.consume()
            }
        }
        area.addEventFilter(KeyEvent.KEY_RELEASED) { typedHandled = false }
        // An action that moves the focus away sends the release elsewhere.
        area.focusedProperty().addListener { _, _, focused -> if (!focused) typedHandled = false }
    }

    override val file: ObservableValue<Path> = fileWrapper.readOnlyProperty

    /** The view shows [to] now. */
    fun moved(to: Path) = fileWrapper.set(to)

    /** The view was extended with the extensions of [host]'s window. */
    fun attached(host: FileViewHost) {
        attachedHost = host
    }

    /** The view's file was saved. */
    fun saved() {
        for (listener in savedListeners.toList()) {
            try {
                listener()
            } catch (e: Exception) {
                log.log(System.Logger.Level.ERROR, "A listener of saving ${file.value} failed", e)
            }
        }
    }

    /** The providers of the items of the context menu, in the order they were added. */
    val contextMenuItems: List<(EditorMenuTarget) -> List<MenuItem>> get() = menuItems.toList()

    override val lineCount: Int get() = area.paragraphs.size

    override fun lineText(line: Int): String = area.getText(line)

    override val shownLines: IntRange
        get() =
            if (area.visibleParagraphs.isEmpty()) {
                IntRange.EMPTY
            } else {
                area.firstVisibleParToAllParIndex()..area.lastVisibleParToAllParIndex()
            }

    override fun onShownLinesChanged(action: () -> Unit): Subscription = area.onShownParagraphsChanged(action)

    override fun addContextMenuItems(items: (EditorMenuTarget) -> List<MenuItem>) {
        menuItems += items
    }

    override val text: String get() = area.text

    override val caretOffset: Int get() = area.caretPosition

    override val selection: IndexRange get() = area.selection

    override fun onTextChanged(action: () -> Unit): Subscription {
        val changes = area.plainTextChanges().subscribe { action() }
        return Subscription { changes.unsubscribe() }
    }

    override fun addToolbarItems(nodes: List<Node>): AutoCloseable = additions.toolbar.add(nodes)

    override fun addBanner(node: Node): AutoCloseable = additions.addBanner(node)

    override fun lock(): AutoCloseable = additions.locks.lock()

    override fun overlay(
        layer: String,
        ranges: List<StyledRange>,
    ) = additions.layers.set(layer, ranges)

    override fun overlay(
        layer: String,
        ranges: List<StyledRange>,
        followEdits: Boolean,
    ) = additions.layers.set(layer, ranges, followEdits)

    override fun reveal(
        start: Int,
        end: Int,
    ) = area.revealRange(start, end)

    override fun addStylesheet(url: String): AutoCloseable {
        area.stylesheets += url
        return AutoCloseable { area.stylesheets -= url }
    }

    override fun <T : Event> addEventFilter(
        type: EventType<T>,
        filter: EventHandler<in T>,
    ): Subscription {
        area.addEventFilter(type, filter)
        return Subscription { area.removeEventFilter(type, filter) }
    }

    override fun addActivity(activity: ObservableValue<ViewActivity>): Subscription = additions.activities.add(activity)

    override val host: FileViewHost get() = attachedHost ?: super.host

    override fun onTextChange(listener: (TextChange) -> Unit): AutoCloseable = additions.textChanges.add(listener)

    override val caretOffsetProperty: ReadOnlyIntegerProperty = caretOffsetWrapper.readOnlyProperty

    override fun moveCaret(offset: Int) {
        area.moveTo(offset.coerceIn(0, area.length))
        area.requestFollowCaret()
    }

    override fun offsetAt(event: MouseEvent): Int? {
        if (area.inGutter(event.target)) return null
        val local = area.screenToLocal(event.screenX, event.screenY) ?: return null
        val character = area.hit(local.x, local.y).characterIndex
        return if (character.isPresent) character.asInt else null
    }

    override fun screenBounds(
        start: Int,
        end: Int,
    ): Bounds? {
        if (area.scene?.window?.isShowing != true) return null
        if (generateSequence<Node>(area) { it.parent }.any { !it.isVisible }) return null
        val from = start.coerceIn(0, area.length)
        val to = end.coerceIn(from, area.length)
        // Fresh cells, e.g. right after an edit.
        area.layout()
        return area.getCharacterBoundsOnScreen(from, to).orElse(null)
    }

    override fun positionOf(offset: Int): TextPosition {
        // Forward: an offset after a line break is the start of the next line, not past the end of its own.
        val position = area.offsetToPosition(offset.coerceIn(0, area.length), Bias.Forward)
        return TextPosition(position.major, position.minor)
    }

    override fun offsetOf(position: TextPosition): Int = area.offsetOf(position.line, position.column)

    override fun addKeyBinding(
        keys: KeyCombination,
        action: () -> Boolean,
    ): AutoCloseable {
        // A filter, so that it comes before the input maps of the view and of RichTextFX, which are handlers.
        val filter =
            EventHandler<KeyEvent> { event ->
                if (!event.isConsumed && keys.match(event) && handles(keys, action)) {
                    event.consume()
                    typedHandled = true
                }
            }
        area.addEventFilter(KeyEvent.KEY_PRESSED, filter)
        return AutoCloseable { area.removeEventFilter(KeyEvent.KEY_PRESSED, filter) }
    }

    /** Whether [action], bound to [keys], handled the press; one that throws is logged and did not. */
    private fun handles(
        keys: KeyCombination,
        action: () -> Boolean,
    ): Boolean =
        try {
            action()
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "The key binding ${keys.displayText} of ${file.value} failed", e)
            false
        }

    override fun applyEdits(edits: List<OffsetEdit>): Boolean {
        if (!view.canReplaceText) return false
        // A server's \r\n never reaches the area, which holds \n line breaks (as replaceText does).
        val sorted = sortedEdits(edits.map { it.copy(text = LineEnding.normalize(it.text)) }, area.length)
        if (sorted.isEmpty()) return true
        val selection = Selection(offsetAfterEdits(area.anchor, sorted), offsetAfterEdits(area.caretPosition, sorted))
        // One multi-change: one undo step of its own, as Replace All; it keeps the moved caret in view.
        area.applyEdits(sorted.map { TextEdit(it.start, it.end, it.text, selection) }, selection)
        return true
    }

    override val dirty: ObservableBooleanValue get() = view.dirty

    override fun onSaved(listener: () -> Unit): AutoCloseable {
        // A wrapper of its own, so that the same listener added twice is removed once per handle.
        val added: () -> Unit = { listener() }
        savedListeners += added
        return AutoCloseable { savedListeners.remove(added) }
    }

    override fun setTextCursor(cursor: Cursor?) {
        if (!area.cursorProperty().isBound) area.cursorProperty().bind(textCursor)
        textCursor.set(cursor ?: Cursor.TEXT)
    }
}
