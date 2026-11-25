package dev.sluide.ide.files

import dev.sluide.fileexplorer.FileAction
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * The explorer's Open in Default App for a regular, non-executable file: hands its `file:` URI to [openUri], which
 * opens it in the operating system's default application (the window's `openWebPage`, `HostServices.showDocument`;
 * it runs on the calling thread and reports no failure). Executable files are not offered, as the default handler
 * of some systems runs them. When a tab of the file has unsaved changes, it asks first (ADR 0089).
 */
internal class WindowDefaultAppAction(
    private val openUri: (URI) -> Unit,
    private val isDirty: (Path) -> Boolean,
    private val save: (Path) -> Boolean,
    /** Asks what to do with the unsaved changes of the file; tests replace it, since it blocks. */
    private val ask: (Path) -> UnsavedChoice,
) {
    private val labels = TextToolLabels.OPEN_IN_DEFAULT_APP

    /** The action in the explorer's context menu, for one file. */
    val action =
        FileAction(
            labels.action,
            icon = labels.icon,
            appliesTo = { Files.isRegularFile(it) && !Files.isExecutable(it) },
            perform = ::perform,
        )

    private fun perform(file: Path) {
        if (mayUseSaved(file, isDirty, save, ask)) openUri(file.toUri())
    }
}
