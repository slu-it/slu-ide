package dev.sluide.ide.git

import dev.sluide.editor.EditorArea
import dev.sluide.fileviewers.FileViewers
import dev.sluide.git.GitChange
import dev.sluide.gitlog.CommitFileDiff
import dev.sluide.gitlog.WorkingTreeFileDiff
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

private val log = System.getLogger(GitDiffTabs::class.java.name)

/**
 * The side-by-side diffs of the git log in tabs of the [editorArea] (ADRs 0038, 0044). [showStatus] tells the git log
 * that a file is not shown as text.
 */
internal class GitDiffTabs(
    private val editorArea: EditorArea,
    private val fileViewers: FileViewers,
    private val showStatus: (String) -> Unit,
) {
    /**
     * Shows what a commit changed in a file side by side in a tab of its own, or selects the tab that already shows it
     * (ADR 0038); says so in the git log if the file is not shown as text.
     */
    fun showCommitDiff(diff: CommitFileDiff) {
        editorArea.tabs
            .filterIsInstance<CommitDiffTab>()
            .firstOrNull { it.shows(diff) }
            ?.let { return editorArea.select(it) }
        val view = fileViewers.openDiff(nameOf(diff.path), diff.current, diff.toTextDiff())
        if (view == null) {
            showStatus("${diff.path.substringAfterLast('/')} cannot be shown as text.")
            return
        }
        editorArea.open(CommitDiffTab(diff, view))
    }

    /**
     * Shows how a file differs between a commit and the working tree side by side in a tab of its own, the file on disk
     * on the right where the JVM can read it (else the text read with the diff, also empty for a deleted file), and
     * focuses it; a tab that shows the same comparison already shows it read again instead (ADR 0044). `false` if the
     * file is not shown as text.
     */
    fun showWorkingTreeDiff(diff: WorkingTreeFileDiff): Boolean {
        val file = liveFile(diff)
        val textDiff = diff.toTextDiff(unsaved = file != null && editorArea.dirtyUnder(file).isNotEmpty())
        val view =
            try {
                if (file != null) {
                    fileViewers.openDiff(file, textDiff)
                } else {
                    fileViewers.openDiff(nameOf(diff.path), diff.current, textDiff)
                }
            } catch (e: IOException) {
                log.log(System.Logger.Level.WARNING, "Cannot open the diff of ${diff.path}", e)
                null
            } ?: return false
        val tab = editorArea.tabs.filterIsInstance<WorkingTreeDiffTab>().firstOrNull { it.shows(diff) }
        if (tab != null) {
            tab.replace(diff, view)
            editorArea.select(tab)
        } else {
            editorArea.open(WorkingTreeDiffTab(diff, view))
        }
        view.node.requestFocus()
        return true
    }
}

/** The file of [diff] on disk, if it is a regular file there that the JVM can name; not for a deleted one. */
internal fun liveFile(diff: WorkingTreeFileDiff): Path? {
    if (diff.change == GitChange.DELETED) return null
    val file =
        try {
            diff.root.resolve(diff.path)
        } catch (_: InvalidPathException) {
            return null
        }
    return file.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
}

/**
 * The repository path [path] as a [Path] that picks the file's viewer by its name; characters that file names
 * cannot have in the JVM's locale (non-ASCII ones in a plain ASCII locale) are replaced, keeping the extension.
 */
internal fun nameOf(path: String): Path =
    try {
        Path.of(path)
    } catch (_: InvalidPathException) {
        Path.of(path.substringAfterLast('/').map { if (it.code < 128) it else '_' }.joinToString(""))
    }
