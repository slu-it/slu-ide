package dev.sluide.ide.scripts

import dev.sluide.fileexplorer.FileAction
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewer
import dev.sluide.fileviewers.RunnableFileView
import dev.sluide.fileviewers.RunnableFileViewer
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignP
import java.nio.file.Path

/**
 * The explorer's Run for files whose viewer can run them, e.g. shell scripts (ADR 0089): opens the file's tab (or
 * selects it) and runs it there, with the questions the view asks first.
 */
internal class WindowScriptRuns(
    /** The viewer that would show a file (`FileViewers.viewerFor`). */
    private val viewerFor: (Path) -> FileViewer?,
    /** Shows a file in its tab (`EditorArea.open`); `false` if it cannot be shown. */
    private val open: (Path) -> Boolean,
    /** The view of a file's tab (`EditorArea.viewOf`). */
    private val viewOf: (Path) -> FileView?,
) {
    /** Run in the explorer's context menu, for one runnable file. */
    val action =
        FileAction(
            "Run",
            icon = { FontIcon(MaterialDesignP.PLAY) },
            appliesTo = { viewerFor(it) is RunnableFileViewer },
            perform = { if (open(it)) (viewOf(it) as? RunnableFileView)?.run() },
        )
}
