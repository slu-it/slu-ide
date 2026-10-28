package dev.sluide.ide.todos

import dev.sluide.editorarea.EditorArea
import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.Highlighters
import dev.sluide.ide.isMac
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import dev.sluide.todos.DescriptionEditor
import dev.sluide.todos.TodoPanel
import javafx.scene.Node
import javafx.scene.control.MenuItem
import javafx.scene.control.SplitPane
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.layout.Region
import java.net.URI
import java.nio.file.Path

/** The width of the expanded TODO panel until it was resized. */
internal const val TODO_PANEL_WIDTH = 320.0

/** The window width the default position assumes while the split's width is not known yet. */
private const val UNKNOWN_TOTAL = 1000.0

/**
 * The position of the divider before a panel at the right end of a split that is [total] pixels wide, so that the panel
 * is [panelWidth] pixels wide; the position is that of the divider's middle, and [dividerWidth] is the divider's width.
 * While [total] is unknown, the panel gets its width in a split of 1000 px.
 */
internal fun todoDividerPosition(
    total: Double,
    panelWidth: Double,
    dividerWidth: Double,
): Double {
    val known = if (total > 0) total else UNKNOWN_TOTAL
    return (1 - (panelWidth + dividerWidth / 2) / known).coerceIn(0.0, 1.0)
}

/**
 * The TODO panel at the right of the main window and its integration: its place in the window's split, the View menu's
 * TODOs item, and the wiring with the editor area and the editor font (ADR 0102). Used on the JavaFX thread.
 */
internal class WindowTodos(
    private val todoPanel: TodoPanel,
    private val editorArea: EditorArea,
    fontSettings: FontSettings,
    private val openWebPage: (URI) -> Unit,
) {
    /** The editor font as CSS, for the description editors of the dialog. */
    private val editorCss = fontSettings.font(FontArea.EDITOR).map(FontSpec::toCss)

    /** The main split the panel is the last item of; set by [attach]. */
    private var split: SplitPane? = null

    /** The width of the expanded panel in pixels, kept while it is collapsed. */
    private var panelWidth = TODO_PANEL_WIDTH

    /** Wires the panel with the editor area and puts it at the right end of [split]. */
    fun attach(split: SplitPane) {
        this.split = split
        // The window's width goes to the editor; the panel keeps its width.
        SplitPane.setResizableWithParent(todoPanel, false)
        split.items.add(todoPanel)
        todoPanel.openWebPage = openWebPage
        todoPanel.openFile = { editorArea.open(it) }
        todoPanel.descriptionEditor = ::descriptionEditor
        // Collapsing the panel with the focus in it gives the focus to the editor; expanding focuses the panel by
        // itself.
        todoPanel.returnFocus = editorArea::focusSelected
        todoPanel.expanded.subscribe { _, expanded ->
            // Only a width it was laid out with while expanded (its content is already gone, so its minimum width is
            // the stripe's).
            if (!expanded && todoPanel.width > todoPanel.minWidth(-1.0) + 1) panelWidth = todoPanel.width
            fit()
        }
        fit()
    }

    /**
     * Collapsed, the panel gets the width of its stripe and cannot be widened by its divider; expanded, it gets its last width
     * in pixels back, even if the window was resized meanwhile. Call it after the other dividers were set.
     */
    fun fit() {
        val split = split ?: return
        val last = split.items.size - 2
        if (last < 0) return
        val expanded = todoPanel.expanded.value
        todoPanel.maxWidth = if (expanded) Region.USE_COMPUTED_SIZE else Region.USE_PREF_SIZE
        // The stripe's own (preferred) width while collapsed.
        val width = if (expanded) panelWidth else todoPanel.prefWidth(-1.0)
        val divider =
            split
                .lookupAll(".split-pane-divider")
                .filter { it.parent === split }
                .maxByOrNull { it.layoutX }
        split.setDividerPosition(last, todoDividerPosition(split.width, width, divider?.prefWidth(-1.0) ?: 0.0))
    }

    /** The View menu's item that expands and collapses the panel; Alt+6 (Cmd+6 on macOS). */
    fun menuItem(): MenuItem =
        MenuItem("TODOs").apply {
            val modifier = if (isMac) KeyCombination.SHORTCUT_DOWN else KeyCombination.ALT_DOWN
            accelerator = KeyCodeCombination(KeyCode.DIGIT6, modifier)
            setOnAction { todoPanel.expanded.set(!todoPanel.expanded.get()) }
        }

    /** Makes the dialog's description field: a Markdown code editor in the editor font. */
    private fun descriptionEditor(text: String): DescriptionEditor {
        val editor = CodeEditors.text(text, Highlighters.markdown)
        editor.node.styleProperty().bind(editorCss)
        return object : DescriptionEditor {
            override val node: Node get() = editor.node
            override val text: String get() = editor.text

            override fun focus() = editor.focus()
        }
    }

    /** The window's project folder reaches the panel (null: none). */
    fun open(folder: Path?) = todoPanel.open(folder)

    /** Stops the panel following the TODOs; called when the window is closed. */
    fun dispose() = todoPanel.close()
}
