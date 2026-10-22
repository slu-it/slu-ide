package dev.sluide.ide.git

import dev.sluide.editorarea.EditorTab
import dev.sluide.fileviewers.DiffFileView
import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TextDiff
import dev.sluide.git.GitChange
import dev.sluide.gitlog.WorkingTreeFileDiff
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.layout.StackPane

/** As many characters of a hash as the git log shows. */
private const val SHORT_HASH = 8

/**
 * How a file differs between a commit and the working tree, side by side in a read-only tab (ADR 0044): the [view] of
 * [diff] (whose right side is the file on disk, or the text read with the diff, e.g. for a deleted file), titled with
 * the file's name and what it is compared with. There is no editor to go back to. [replace] shows the same comparison
 * read again, whatever changed meanwhile (e.g. a deleted file that is back).
 */
internal class WorkingTreeDiffTab(
    diff: WorkingTreeFileDiff,
    private var view: DiffFileView,
) : EditorTab {
    var diff = diff
        private set

    override val title: ObservableValue<String> =
        ReadOnlyStringWrapper("${diff.path.substringAfterLast('/')} (${diff.label} ↔ Working Tree)").readOnlyProperty

    override val tooltip = "${diff.path}: ${diff.commitTitle()} ↔ Working Tree"

    override val usesEditorFont = true
    override val closableByShortcut = true

    private val holder = StackPane(view.node)

    override val content: Node get() = holder

    /** Whether this tab compares the same file of the same commit as [other]. */
    fun shows(other: WorkingTreeFileDiff) =
        diff.root == other.root && diff.commit == other.commit && diff.path == other.path

    /** Shows [other], the same comparison read again, in [view] instead of the view shown so far. */
    fun replace(
        other: WorkingTreeFileDiff,
        view: DiffFileView,
    ) {
        this.view.dispose()
        this.view = view
        diff = other
        holder.children.setAll(view.node)
    }

    override fun dispose() = view.dispose()
}

/** The commit as the user chose it and its short hash, `feature (1a2b3c4d)`, or only the short hash. */
private fun WorkingTreeFileDiff.commitTitle(): String {
    val short = commit.take(SHORT_HASH)
    return if (label == short) short else "$label ($short)"
}

/**
 * [this] as a diff of texts: the commit above the original (with the old path of a renamed file), `(none)` for a file
 * added in the working tree, and "Working Tree" above the file ("Working Tree (deleted)" for a deleted one, "Working
 * Tree (saved)" if an editor has [unsaved] changes of it, as for the diff against `HEAD`, ADR 0021).
 */
internal fun WorkingTreeFileDiff.toTextDiff(unsaved: Boolean = false): TextDiff {
    val originalTitle =
        when {
            change == GitChange.ADDED -> "(none)"
            oldPath != null && oldPath != path -> "${commitTitle()}, $oldPath"
            else -> commitTitle()
        }
    val currentTitle =
        when {
            change == GitChange.DELETED -> "Working Tree (deleted)"
            unsaved -> "Working Tree (saved)"
            else -> "Working Tree"
        }
    return TextDiff(original, hunks.map { LineChange(it.original, it.current) }, originalTitle, currentTitle)
}
