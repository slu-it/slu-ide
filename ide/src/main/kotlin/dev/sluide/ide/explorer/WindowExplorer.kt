package dev.sluide.ide.explorer

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.editorarea.EditorArea
import dev.sluide.fileexplorer.ExplorerBar
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.ide.isMac
import javafx.beans.InvalidationListener
import javafx.beans.Observable
import javafx.scene.control.MenuItem
import javafx.scene.control.SplitPane
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.layout.Region
import java.nio.file.Path

/** The window width the position assumes while the split's width is not known yet. */
private const val UNKNOWN_TOTAL = 1000.0

/**
 * The position of the divider after a panel at the left end of a split that is [total] pixels wide, so that the panel
 * is [width] pixels wide; the position is that of the divider's middle, and [dividerWidth] is the divider's width.
 * While [total] is unknown, the panel gets its width in a split of 1000 px.
 */
internal fun explorerDividerPosition(
    total: Double,
    width: Double,
    dividerWidth: Double,
): Double {
    val known = if (total > 0) total else UNKNOWN_TOTAL
    return ((width + dividerWidth / 2) / known).coerceIn(0.0, 1.0)
}

/**
 * The left column of the main window, the file explorer, and its integration: its place in the window's split, its
 * collapsing, the View menu's Explorer item and the wiring with the editor area (ADR 0104). Used on the JavaFX thread.
 */
internal class WindowExplorer(
    private val fileExplorer: FileExplorerView,
    private val backgroundTasks: BackgroundTasks,
    private val editorArea: EditorArea,
) {
    /** The column; made by [attach]. */
    lateinit var panel: ExplorerPanel
        private set

    /** The main split the column is the first item of; set by [attach]. */
    private var split: SplitPane? = null

    /** The width of the expanded column in pixels, kept while it is collapsed; the start width once known. */
    private var width = 0.0

    /** Makes the column with [bar] and puts it at the left end of [split]. */
    fun attach(
        split: SplitPane,
        bar: ExplorerBar,
    ) {
        this.split = split
        val panel = ExplorerPanel(bar, fileExplorer, backgroundTasks)
        this.panel = panel
        // Resizing the window resizes the editor area; the explorer keeps its width until the user drags the divider
        // (or the editor area is as narrow as it can be).
        SplitPane.setResizableWithParent(panel, false)
        split.items.add(0, panel)
        // Collapsing the column with the focus in it gives the focus to the editor; expanding focuses the tree by
        // itself.
        panel.returnFocus = editorArea::focusSelected
        panel.expanded.subscribe { _, expanded ->
            // Only a width it was laid out with while expanded (its content is already gone, so its minimum width is
            // the stripe's).
            if (!expanded && panel.width > panel.minWidth(-1.0) + 1) width = panel.width
            // The other dividers keep their positions, which are fractions of the split's width, so the TODO panel
            // keeps its width; setting its divider again would undo a width it was dragged to.
            fit()
        }
    }

    /** Collapses the column to its stripe. */
    fun collapse() = panel.expanded.set(false)

    /** Reveals [file] in the tree and focuses it, expanding the column first if it is collapsed. */
    fun select(file: Path) {
        panel.expanded.set(true)
        fileExplorer.reveal(file, focus = true)
    }

    /**
     * Collapsed, the column gets the width of its stripe and cannot be widened by its divider; expanded, it gets its
     * last width in pixels back, even if the window was resized meanwhile.
     */
    private fun fit() {
        val split = split ?: return
        if (split.items.size < 2) return
        val expanded = panel.expanded.value
        panel.maxWidth = if (expanded) Region.USE_COMPUTED_SIZE else Region.USE_PREF_SIZE
        // The stripe's own (preferred) width while collapsed.
        val width = if (expanded) width else panel.prefWidth(-1.0)
        // Views in the editor area may have split panes of their own.
        val divider =
            split
                .lookupAll(".split-pane-divider")
                .filter { it.parent === split }
                .minByOrNull { it.layoutX }
        split.setDividerPosition(0, explorerDividerPosition(split.width, width, divider?.prefWidth(-1.0) ?: 0.0))
    }

    /**
     * Gives the expanded column its start width ([FileExplorerView.startWidth]), but at most half of a narrow window,
     * at once or, before the window is laid out, as soon as it is. Then it calls [afterFit], which sets the dividers
     * behind this one again: a split just laid out, or one the git log just went into, moves them with it.
     */
    fun fitStart(afterFit: () -> Unit) {
        val split = split ?: return

        fun fitNow() {
            width = minOf(fileExplorer.startWidth(), split.width / 2)
            fit()
            afterFit()
        }
        if (split.width > 0) {
            fitNow()
            return
        }
        split.widthProperty().addListener(
            object : InvalidationListener {
                override fun invalidated(observable: Observable) {
                    if (split.width <= 0) return
                    split.widthProperty().removeListener(this)
                    fitNow()
                }
            },
        )
    }

    /** The View menu's item that expands and collapses the column; Alt+1 (Cmd+1 on macOS). */
    fun menuItem(): MenuItem =
        MenuItem("Explorer").apply {
            val modifier = if (isMac) KeyCombination.SHORTCUT_DOWN else KeyCombination.ALT_DOWN
            accelerator = KeyCodeCombination(KeyCode.DIGIT1, modifier)
            setOnAction { panel.expanded.set(!panel.expanded.get()) }
        }
}
