package dev.sluide.ide.compare

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.editorarea.EditorArea
import dev.sluide.fileviewers.DiffFileView
import dev.sluide.fileviewers.FileViewers
import dev.sluide.git.GitFileComparison
import dev.sluide.git.GitFileDiff
import javafx.application.Platform
import java.nio.file.Path
import java.util.concurrent.Executor

/**
 * The explorer's Compare Files of this window (ADR 0113): compares two files with git off the FX thread and shows them
 * side by side in a read-only tab. A pair that is shown already is read again in its tab, which keeps its sides.
 * [executor] runs the comparison and [dispatch] applies its result, the FX thread in the application.
 */
internal class WindowFileComparison(
    private val editorArea: EditorArea,
    private val fileViewers: FileViewers,
    private val comparison: GitFileComparison,
    private val projectFolder: () -> Path?,
    private val showError: (String) -> Unit,
    private val executor: Executor = NamedThreads.single("File comparison"),
    private val dispatch: (Runnable) -> Unit = Platform::runLater,
) {
    private var disposed = false

    /**
     * Drops the results of comparisons still running, as the window is closed. Not unit-tested: reaching the dropped
     * result needs an [EditorArea], a JavaFX control; the check is the first line of [show].
     */
    fun dispose() {
        disposed = true
    }

    /** Compares [upper], the file higher in the explorer's tree and shown on the left, with [lower]. */
    fun compare(
        upper: Path,
        lower: Path,
    ) {
        executor.execute {
            val result = comparison.compare(upper, lower)
            dispatch(Runnable { show(upper, lower, result) })
        }
    }

    private fun show(
        upper: Path,
        lower: Path,
        result: GitFileDiff?,
    ) {
        if (disposed) return
        val message = comparisonMessage(upper, lower, result)
        if (message != null || result !is GitFileDiff.Text) return showError(message ?: "")
        val existing =
            editorArea.tabs.filterIsInstance<FileComparisonTab>().firstOrNull {
                it.comparison.shows(
                    upper,
                    lower,
                )
            }
        val read = FileComparison.of(upper, lower, result)
        // A refreshed tab keeps its sides, e.g. after they were swapped.
        val fresh = if (existing != null) read.inSidesOf(existing.comparison) else read
        val view = viewOf(fresh) ?: return
        val tab = existing ?: FileComparisonTab(fresh, view)
        if (existing != null) existing.replace(fresh, view)
        wireSwap(tab, view)
        if (existing != null) editorArea.select(tab) else editorArea.open(tab)
        view.node.requestFocus()
    }

    /** A view of [comparison], or `null` after telling the user that its right file is not shown as text. */
    private fun viewOf(comparison: FileComparison): DiffFileView? {
        val textDiff = comparison.toTextDiff(titleOf(comparison.left), titleOf(comparison.right))
        val view = fileViewers.openDiff(comparison.right, comparison.rightText, textDiff)
        if (view == null) showError("${comparison.right.fileName} cannot be shown as text.")
        return view
    }

    private fun titleOf(file: Path) = columnTitle(file, projectFolder(), editorArea.dirtyUnder(file).isNotEmpty())

    /** Swap Sides of [view] shows the swapped comparison in a new view of [tab], in memory. */
    private fun wireSwap(
        tab: FileComparisonTab,
        view: DiffFileView,
    ) {
        view.onSwap = {
            val swapped = tab.comparison.swapped()
            viewOf(swapped)?.let { next ->
                tab.replace(swapped, next)
                wireSwap(tab, next)
                next.node.requestFocus()
            }
        }
    }
}
