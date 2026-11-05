package dev.sluide.fileviewers

import javafx.beans.value.ObservableValue
import javafx.event.Event
import javafx.event.EventHandler
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.control.IndexRange
import javafx.scene.control.MenuItem
import javafx.util.Subscription
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
    internal val extensions: List<CodeEditorExtension> get() = added.toList()
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
