package dev.sluide.fileviewers

import dev.sluide.textediting.TextPosition
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.ReadOnlyIntegerWrapper
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
import javafx.scene.input.MouseEvent
import javafx.util.Subscription
import java.net.URI
import java.nio.file.Path

/**
 * Window service (ADR 0070, 0108): features that extend every code editor of the window, e.g. with gutter columns or
 * context menu items. A code editor is extended once, when it first gets a host of a window with this service, by the
 * extensions added until then; editors without a host (in dialogs) are not. FX thread.
 */
class CodeEditorExtensions {
    private val added = mutableListOf<CodeEditorExtension>()

    /** Extends the code editors that get a host of this window from now on, after the extensions added before. */
    fun add(extension: CodeEditorExtension) {
        added += extension
    }

    /** The extensions in the order they were added. */
    val extensions: List<CodeEditorExtension> get() = added.toList()
}

/** A feature that extends code editors ([CodeEditorExtensions]); it restricts itself to the file types it wants. */
fun interface CodeEditorExtension {
    /**
     * Called once per code editor, on the FX thread, when it first gets a host of a window with this service; the
     * handle (`null` for none) is closed when the editor is disposed.
     */
    fun extend(editor: ExtensibleCodeEditor): AutoCloseable?
}

/**
 * What a [CodeEditorExtension] can see of and add to a code editor. FX thread. Every member past [addContextMenuItems]
 * has a default (doing nothing, or [text] built from the lines), so fakes in tests implement only what they use.
 */
interface ExtensibleCodeEditor : GutterColumns {
    /** The file shown; changes when the file is moved. */
    val file: ObservableValue<Path>

    /** The number of lines of the text, unsaved changes included; at least 1. */
    val lineCount: Int

    /**
     * The text of [line] (0-based), without its line break. Throws [IndexOutOfBoundsException] for a line outside
     * `0 until lineCount`: a line remembered from before an edit may be gone, so check it against [lineCount].
     */
    fun lineText(line: Int): String

    /** The lines (0-based) shown on screen, empty when none (e.g. not laid out yet). */
    val shownLines: IntRange

    /**
     * Calls [action] whenever the [shownLines] change: when the editor scrolls, is resized or is shown again, or a
     * shown line is edited. Unsubscribing stops it.
     */
    fun onShownLinesChanged(action: () -> Unit): Subscription

    /**
     * Adds items to the code editor's context menu (ADR 0108), shown by the code editor's context menu after its own:
     * [items] is asked each time the menu shows, for where it was opened.
     */
    fun addContextMenuItems(items: (EditorMenuTarget) -> List<MenuItem>)

    /** Whether the editor shows plain text: a file without a language viewer, shown without highlighting. */
    val plainText: Boolean get() = false

    /** The whole text, `\n` line breaks, unsaved changes included; builds it, so not per keystroke. */
    val text: String get() = (0 until lineCount).joinToString("\n") { lineText(it) }

    /** The caret's offset in [text]. */
    val caretOffset: Int get() = 0

    /** The selected range [start, end) of [text]; empty at the caret when nothing is selected. */
    val selection: IndexRange get() = IndexRange(0, 0)

    /** Calls [action] on every change of the text (an edit, a reload), without building it. Unsubscribing stops it. */
    fun onTextChanged(action: () -> Unit): Subscription = Subscription.EMPTY

    /**
     * Adds [nodes] to the editor's toolbar items ([CodeEditor.toolbarItems]): a code editor of its own shows them in a
     * row above the text, an embedding view in its own toolbar. Closing the handle removes them.
     */
    fun addToolbarItems(nodes: List<Node>): AutoCloseable = AutoCloseable {}

    /** Shows [node] as a banner above the text, below the find bar and the view's own banners, until closed. */
    fun addBanner(node: Node): AutoCloseable = AutoCloseable {}

    /**
     * Makes the editor read-only until every returned lock is closed (closing one twice is harmless): typing and
     * pasting change nothing, `canReplaceText` is false, `replaceText` and the find bar's Replace and Replace All
     * refuse; caret, selection, copy, find and scrolling work. Saving still works, and a change on disk still reloads.
     */
    fun lock(): AutoCloseable = AutoCloseable {}

    /**
     * Overlays [ranges] as the layer [layer] over highlighting, masks and find matches, replacing that layer's previous
     * ranges; an empty list clears it. Layers stay through background highlighting and find updates and are all
     * cleared by any change of the text (an edit or a reload). Ranges are clamped to the text; overlapping ones add
     * both classes. The class names must differ from the highlighter's and the find bar's: they are removed by name.
     */
    fun overlay(
        layer: String,
        ranges: List<StyledRange>,
    ) {}

    /**
     * Scrolls [start, end) into view without moving the caret or the selection and without focusing. The start is
     * always shown, the end too when both fit the viewport.
     */
    fun reveal(
        start: Int,
        end: Int,
    ) {}

    /**
     * Adds the stylesheet at [url] (an external form) to the editor's text area, e.g. for the classes of [overlay];
     * closing removes it.
     */
    fun addStylesheet(url: String): AutoCloseable = AutoCloseable {}

    /** Adds an event filter on the text area; unsubscribing removes it. */
    fun <T : Event> addEventFilter(
        type: EventType<T>,
        filter: EventHandler<in T>,
    ): Subscription = Subscription.EMPTY

    /**
     * Shows [activity] in the view's [FileView.activity] while subscribed: the view's activity is RUNNING if any added
     * one is, else FAILED if any is, else NONE.
     */
    fun addActivity(activity: ObservableValue<ViewActivity>): Subscription = Subscription.EMPTY

    // Language features (ADR 0141): what a language-server client needs of the editor.

    /**
     * The host the editor was extended with (extensions attach only with one), e.g. to open another file at a
     * position ([FileViewHost.openFile]), read or edit other tabs' texts or look up a window service. Before the
     * editor is extended, and by default, a host that opens nothing.
     */
    val host: FileViewHost get() = NoHost

    /**
     * Calls [listener] with every change of the text, once per change, in order, synchronously on the FX thread, from
     * any source: typing, undo and redo, a reload or `replaceText` (only the differing range), find and replace,
     * [applyEdits] (one call per edit that changes something; edits that change nothing are left out) and the editing
     * keys. The offsets of a change are those of the text left by the change before it, so applying the changes in
     * order to a copy of the text keeps it equal to [text].
     *
     * Changes made together (Replace All, [applyEdits], undoing them) are told after all of them are applied: inside
     * the callback, [text], [caretOffset], [positionOf] and [offsetOf] already reflect the whole batch, not the change
     * at hand. A consumer that needs positions per change (a language server's ranges) keeps its own copy of the text
     * and computes them there, applying the deltas in order. Changing the text from inside the callback re-enters the
     * telling; do it later (`Platform.runLater`). Closing the handle stops it; a listener that throws is logged, the
     * others still get the change.
     */
    fun onTextChange(listener: (TextChange) -> Unit): AutoCloseable = AutoCloseable {}

    /** [caretOffset] as it changes. */
    val caretOffsetProperty: ReadOnlyIntegerProperty get() = ReadOnlyIntegerWrapper(caretOffset).readOnlyProperty

    /**
     * Moves the caret to [offset] (clamped to the text), dropping the selection, and scrolls it into view; the focus
     * stays where it is (unlike [FileView.showPosition], which focuses the text).
     */
    fun moveCaret(offset: Int) {}

    /**
     * The offset of the character under the mouse of [event] (a mouse event on the text area, e.g. from
     * [addEventFilter]); `null` over the gutter, past the end of a line's text or below the last line.
     */
    fun offsetAt(event: MouseEvent): Int? = null

    /**
     * The screen bounds of the characters [start, end) (clamped to the text), or of a caret at [start] when [end] is
     * [start], e.g. to place a popup; `null` while the editor is not shown or the range is scrolled out of view.
     */
    fun screenBounds(
        start: Int,
        end: Int = start,
    ): Bounds? = null

    /** The line and column (0-based, UTF-16 units) of [offset] (clamped to the text) in [text]. */
    fun positionOf(offset: Int): TextPosition = TextPosition.of(text, offset)

    /** The offset of [position] in [text], with line and column clamped to the text (as [TextPosition.offsetIn]). */
    fun offsetOf(position: TextPosition): Int = position.offsetIn(text)

    /**
     * Gives key presses matching [keys] to [action] before the editor's own key bindings and RichTextFX's, while the
     * text area has the focus. [action] returns whether it handled the key: then the press is consumed, otherwise the
     * editor handles it as usual (e.g. a completion popup takes Enter only while it shows). Bindings are event filters
     * of the text area, asked in the order added together with those of [addEventFilter]; a binding skips a press that
     * an earlier one consumed. A handled press also consumes the key typed that follows it, so that Alt+J or an Option
     * chord on macOS types nothing. An [action] that throws is logged and counts as not handled. Use
     * `KeyCombination.SHORTCUT_DOWN` for Ctrl/Cmd, `CONTROL_DOWN` for a literal Ctrl (Ctrl+Space). Closing the handle
     * removes the binding.
     */
    fun addKeyBinding(
        keys: KeyCombination,
        action: () -> Boolean,
    ): AutoCloseable = AutoCloseable {}

    /**
     * Applies [edits] (offsets in the current [text], not overlapping, in any order; inserts at the same offset appear
     * in the order given) as one undo step, each edit a change for [onTextChange]; `\r\n` and `\r` in their texts become
     * `\n`, as the editor holds the text. Caret and selection move with the text (an edit ending at or before them
     * shifts them, one containing them puts them after its new text), and the caret is kept in view. Returns
     * `false` and changes nothing while the editor cannot be edited (read-only for its encoding, [lock]ed); `true`
     * otherwise, also for no edits. Throws [IllegalArgumentException] for edits outside the text or overlapping.
     */
    fun applyEdits(edits: List<OffsetEdit>): Boolean = false

    /** Whether the editor has unsaved changes. */
    val dirty: ObservableBooleanValue get() = ReadOnlyBooleanWrapper(false).readOnlyProperty

    /** Calls [listener] after each successful save of the editor's file; closing the handle stops it. */
    fun onSaved(listener: () -> Unit): AutoCloseable = AutoCloseable {}

    /** Shows [cursor] over the text, e.g. a hand while Shortcut is held over a link; `null` for the text cursor. */
    fun setTextCursor(cursor: Cursor?) {}

    /**
     * The text area while it is in a scene, `null` otherwise: the owner of popups at the caret or the mouse (completion,
     * hover), whose window, focus and scene they follow (ADR 0145); also the node whose focus navigation checks (the
     * scene's focus owner, the window's focus) and gives back after a jump (`requestFocus()`, ADR 0146). Only for
     * that: not to add children, styles or handlers, which go through the other members.
     */
    val popupOwner: Node? get() = null

    /**
     * Overlays [ranges] as [layer] like [overlay], but with [followEdits] the layer follows edits instead of being
     * cleared: ranges entirely before an edited range stay, ranges entirely after it move with the text, and ranges
     * that overlap or touch it are dropped (the owner sets them again). A reload counts as an edit of the range that
     * differs. Without [followEdits], as [overlay].
     */
    fun overlay(
        layer: String,
        ranges: List<StyledRange>,
        followEdits: Boolean,
    ) = overlay(layer, ranges)
}

/**
 * A change of an editor's text ([ExtensibleCodeEditor.onTextChange]): from [start] (a UTF-16 offset), [removed] was
 * replaced by [inserted]. The text before [start] is unchanged, so the removed range ended at `start + removed.length`
 * in the text before the change.
 */
data class TextChange(
    val start: Int,
    val removed: String,
    val inserted: String,
)

/** Replaces [start, end) of an editor's text by [text] ([ExtensibleCodeEditor.applyEdits]); UTF-16 offsets. */
data class OffsetEdit(
    val start: Int,
    val end: Int,
    val text: String,
)

/** The [ExtensibleCodeEditor.host] of fakes: opens nothing. */
private object NoHost : FileViewHost {
    override fun openFile(file: Path): Boolean = false

    override fun openWebPage(uri: URI) {}
}

/**
 * Where a context menu was opened: 0-based line and column of the caret (moved to a right-click position first), and
 * the selected text, empty without a selection (ADR 0112).
 */
data class EditorMenuTarget(
    val line: Int,
    val column: Int,
    val selectedText: String = "",
)
