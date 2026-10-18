package dev.sluide.gitlog

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitFileDiff
import dev.sluide.gitlog.data.FileDiffs
import dev.sluide.gitlog.data.GitLogPages
import dev.sluide.gitlog.data.GitLogRow
import dev.sluide.gitlog.data.GitLogSource
import dev.sluide.gitlog.ui.SHORT_HASH
import javafx.stage.Stage
import javafx.stage.Window
import javafx.stage.WindowEvent
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ExecutorService

/**
 * Shows how files differ: between a commit and its parent ([showDiff], [showCommitDiff], ADR 0038), and between a
 * commit and the working tree ([compareWithLocal], [showHeadDiff], [showWorkingTreeDiff], ADR 0044, 0046 and 0048).
 * The diffs go to [showFileDiff] and [showWorkingTreeFileDiff], files to open to [openFile]; what keeps a diff from being
 * shown is told through [tell], unless [selections] counted another change of the selection meanwhile. Working-tree
 * reads run on [background]; their results are handled through [dispatch].
 */
internal class CommitDiffs(
    private val pages: GitLogPages,
    private val source: GitLogSource,
    private val fileDiffs: FileDiffs,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    private val window: () -> Window?,
    private val selections: () -> Long,
    private val fileExists: (Path) -> Boolean,
    private val tell: (String) -> Unit,
) {
    /** Shows what a commit changed in a file; set by the view (ADR 0038). */
    var showFileDiff: (CommitFileDiff) -> Unit = {}

    /** Opens a file of the working tree; set by the view. */
    var openFile: (Path) -> Unit = {}

    /** Shows how a file differs from the working tree; `false` if it cannot be shown as text; set by the view. */
    var showWorkingTreeFileDiff: (WorkingTreeFileDiff) -> Boolean = { true }

    /** The open working-tree windows by the revision they compare (ADR 0044). */
    internal val workingTreeWindows = LinkedHashMap<String, WorkingTreeWindow>()

    /**
     * Reads how [file] differs between `HEAD` ([head]) and the working tree on [reader] (the Commit tab's thread) and
     * hands it to [showWorkingTreeFileDiff], labelled "HEAD" (ADR 0048); an added or untracked file is all new. A
     * binary, too large or unchanged file, a submodule or a failure is told in the header instead, unless the selection
     * changed meanwhile. Without a `HEAD` commit (no commit yet) there is nothing to compare with: the file itself is
     * opened with [openFile], if it is in the working tree.
     */
    fun showHeadDiff(
        root: Path,
        reader: ExecutorService,
        file: GitChangedFile,
        head: String?,
    ) {
        if (head == null) {
            if (inWorkingTree(root, file.path)) localFile(file)?.let(openFile)
            return
        }
        val requestedAt = selections()
        tell("")
        reader.execute {
            val diff = source.workingTreeFileDiff(root, head, file)
            dispatch {
                if (pages.root != root) return@dispatch
                val message =
                    workingTreeMessage(file.path.substringAfterLast('/'), file, diff) { text ->
                        showWorkingTreeFileDiff(
                            WorkingTreeFileDiff(
                                root,
                                file.path,
                                file.oldPath,
                                file.change,
                                head,
                                "HEAD",
                                text.original,
                                text.current,
                                text.hunks,
                            ),
                        )
                    }
                if (message != null && selections() == requestedAt) tell(message)
            }
        }
    }

    /**
     * Reads how the file of the history [key] at the commit of [row] ([file]: its change there) differs from the file
     * in the working tree (at the history's path) in the background, and hands it to [showWorkingTreeFileDiff]; a binary,
     * too large or unchanged file, a submodule or a failure is told in the header instead, unless the selection changed
     * meanwhile (ADR 0046).
     */
    fun compareWithLocal(
        key: FileHistoryKey,
        row: GitLogRow,
        file: GitChangedFile,
    ) {
        val root = pages.root ?: return
        val commit = row.commit.hash
        val requestedAt = selections()
        tell("")
        background {
            val compared =
                when {
                    // Not at the commit: all of the working-tree file is new.
                    file.change == GitChange.DELETED -> GitChangedFile(GitChange.ADDED, key.path)

                    !inWorkingTree(root, key.path) -> GitChangedFile(GitChange.DELETED, file.path)

                    file.path != key.path -> GitChangedFile(GitChange.RENAMED, key.path, file.path)

                    else -> GitChangedFile(GitChange.MODIFIED, key.path)
                }
            val diff = source.workingTreeFileDiff(root, commit, compared)
            dispatch {
                if (pages.root != root) return@dispatch
                val message =
                    workingTreeMessage(key.name, compared, diff) { text ->
                        showWorkingTreeFileDiff(
                            WorkingTreeFileDiff(
                                root,
                                compared.path,
                                compared.oldPath,
                                compared.change,
                                commit,
                                commit.take(SHORT_HASH),
                                text.original,
                                text.current,
                                text.hunks,
                            ),
                        )
                    }
                if (message != null && selections() == requestedAt) tell(message)
            }
        }
    }

    /**
     * What keeps the working-tree [diff] of the file [name] ([compared]) from being shown; `null` once [show] showed it.
     */
    private fun workingTreeMessage(
        name: String,
        compared: GitChangedFile,
        diff: GitFileDiff?,
        show: (GitFileDiff.Text) -> Boolean,
    ): String? =
        when (diff) {
            null -> {
                "The diff could not be read."
            }

            GitFileDiff.Binary -> {
                "$name is a binary file."
            }

            GitFileDiff.TooLarge -> {
                "$name is too large to compare."
            }

            is GitFileDiff.Submodule -> {
                "$name is a submodule."
            }

            is GitFileDiff.Text -> {
                val same = diff.original == diff.current && compared.change.changesContent
                when {
                    same -> "$name is the same in the working tree."
                    show(diff) -> null
                    else -> "$name cannot be shown as text."
                }
            }
        }

    /** Whether the working tree has a file at [path]; `true` if the JVM cannot name it (git reads it then). */
    private fun inWorkingTree(
        root: Path,
        path: String,
    ): Boolean =
        try {
            fileExists(root.resolve(path))
        } catch (_: InvalidPathException) {
            true
        }

    /**
     * Opens a window with the files that differ between the commit [revision] names (a full refname, `HEAD` or a full
     * hash) and the working tree, titled with [label] (ADR 0044); one that is open for it already comes to the front. The
     * window is not owned by the IDE window, which comes to the front when a diff opens from it.
     */
    fun showWorkingTreeDiff(
        revision: String,
        label: String,
    ) {
        val root = pages.root ?: return
        workingTreeWindows[revision]?.let { return it.show() }
        val comparison =
            WorkingTreeComparison(
                WorkingTreeTarget(root, revision, label),
                source::workingTreeChanges,
                source::workingTreeFileDiff,
                background,
                dispatch,
            )
        val window =
            WorkingTreeWindow(
                window(),
                comparison,
                { diff -> showWorkingTreeFileDiff(diff).also { shown -> if (shown) bringToFront() } },
                { file -> openFile(file) },
                ::localFile,
            )
        workingTreeWindows[revision] = window
        window.stage.addEventHandler(WindowEvent.WINDOW_HIDDEN) {
            if (workingTreeWindows[revision] === window) workingTreeWindows.remove(revision)
        }
        window.show()
    }

    /** Brings the IDE window in front of the working-tree windows, e.g. for a diff tab opened from one. */
    private fun bringToFront() {
        val window = window() ?: return
        (window as? Stage)?.toFront()
        window.requestFocus()
    }

    /** Closes the working-tree windows, e.g. for another folder or when the IDE window closes. */
    fun closeWorkingTreeWindows() {
        workingTreeWindows.values.toList().forEach(WorkingTreeWindow::close)
        workingTreeWindows.clear()
    }

    /**
     * [file] in the working tree; `null` without a repository or if its name cannot be a path in the JVM's locale
     * (non-ASCII characters in a plain ASCII one).
     */
    fun localFile(file: GitChangedFile): Path? =
        try {
            pages.root?.resolve(file.path)
        } catch (_: InvalidPathException) {
            null
        }

    /**
     * Reads what the commit of [row] changed in [file] and hands it to [showFileDiff]; a binary, too large or unchanged
     * file, a submodule or a failure is told in the header instead, unless the selection changed meanwhile.
     */
    fun showDiff(
        row: GitLogRow,
        file: GitChangedFile,
    ) {
        val commit = row.commit
        val requestedAt = selections()
        showCommitDiff(commit.hash, commit.parents.firstOrNull(), file) { message ->
            if (selections() == requestedAt) tell(message)
        }
    }

    /**
     * Reads what [commit] changed in [file] against [parent] (the first parent, or a push's base, ADR 0049) and hands it
     * to [showFileDiff]; a binary, too large or unchanged file, a submodule or a failure goes to [report] instead.
     */
    fun showCommitDiff(
        commit: String,
        parent: String?,
        file: GitChangedFile,
        report: (String) -> Unit,
    ) {
        val root = pages.root ?: return
        fileDiffs.request(root, commit, parent, file) { diff ->
            val name = file.path.substringAfterLast('/')
            val message =
                when {
                    diff == null -> {
                        "The diff could not be read."
                    }

                    diff == GitFileDiff.Binary -> {
                        "$name is a binary file."
                    }

                    diff == GitFileDiff.TooLarge -> {
                        "$name is too large to compare."
                    }

                    diff is GitFileDiff.Submodule -> {
                        submoduleMessage(name, diff)
                    }

                    // A pure rename or a change of the mode only: no empty diff.
                    diff is GitFileDiff.Text && diff.original == diff.current && file.change.changesContent -> {
                        "$name has no content changes."
                    }

                    else -> {
                        null
                    }
                }
            if (message != null) {
                report(message)
            } else if (diff is GitFileDiff.Text) {
                showFileDiff(
                    CommitFileDiff(
                        root,
                        file.path,
                        file.oldPath,
                        file.change,
                        commit,
                        parent,
                        diff.original,
                        diff.current,
                        diff.hunks,
                    ),
                )
            }
        }
    }

    private fun submoduleMessage(
        name: String,
        diff: GitFileDiff.Submodule,
    ): String {
        val old = diff.old?.take(SHORT_HASH)
        val new = diff.new?.take(SHORT_HASH)
        return when {
            old != null && new != null -> "$name is a submodule: $old → $new."
            new != null -> "$name is a submodule, added at $new."
            else -> "$name is a submodule, removed."
        }
    }
}
