package dev.sluide.ide.git

import dev.sluide.editor.EditorTab
import dev.sluide.fileviewers.DiffFileView
import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TextDiff
import dev.sluide.git.GitChange
import dev.sluide.gitlog.CommitFileDiff
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.beans.value.ObservableValue
import javafx.scene.Node

/** As many characters of a hash as the git log shows (its details pane, IntelliJ). */
private const val SHORT_HASH = 8

/**
 * What a commit changed in a file, side by side in a read-only tab (ADR 0038): the [view] of [diff], titled with the
 * file's name and the commit's short hash. There is no editor to go back to.
 */
internal class CommitDiffTab(
    val diff: CommitFileDiff,
    private val view: DiffFileView,
) : EditorTab {
    private val short = diff.commit.take(SHORT_HASH)

    override val title: ObservableValue<String> =
        ReadOnlyStringWrapper("${diff.path.substringAfterLast('/')} ($short)").readOnlyProperty

    override val tooltip = "${diff.path} at $short"

    override val content: Node get() = view.node

    /** Whether this tab shows the same file of the same commit as [other]. */
    fun shows(other: CommitFileDiff) = diff.root == other.root && diff.commit == other.commit && diff.path == other.path

    override fun dispose() = view.dispose()
}

/**
 * [this] as a diff of texts: the parent's short hash above the original (with the old path of a renamed or copied
 * file), `(none)` for an added file or a root commit, and the commit's short hash above the file as it changed it.
 */
internal fun CommitFileDiff.toTextDiff(): TextDiff {
    val parent = parent
    val originalTitle =
        when {
            change == GitChange.ADDED || parent == null -> "(none)"
            oldPath != null && oldPath != path -> "${parent.take(SHORT_HASH)} ($oldPath)"
            else -> parent.take(SHORT_HASH)
        }
    return TextDiff(original, hunks.map { LineChange(it.original, it.current) }, originalTitle, commit.take(SHORT_HASH))
}
