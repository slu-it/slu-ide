package dev.sluide.fileviewers

import javafx.beans.value.ObservableValue
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

/** What a [CodeEditorExtension] can see of and add to a code editor. FX thread. */
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
