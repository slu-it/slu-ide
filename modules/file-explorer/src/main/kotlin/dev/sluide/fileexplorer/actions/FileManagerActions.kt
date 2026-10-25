package dev.sluide.fileexplorer.actions

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The explorer's file-manager and terminal items (ADR 0093): open a folder in the system file manager, reveal a file
 * in it, open a terminal in a folder. A target is the path of the entry clicked, `null` for the empty area, which
 * stands for the [projectFolder].
 */
internal class FileManagerActions(
    private val projectFolder: () -> Path?,
    private val showError: (String) -> Unit,
    private val runInBackground: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    private val openInTerminal: () -> ((Path) -> Unit)?,
) {
    /** The file manager of the operating system, for Open in Finder/Explorer/File Manager. */
    var fileManager: SystemFileManager = systemFileManager()

    /** Starts the command of the file manager; tests replace it. */
    var launchCommand: (List<String>) -> Unit = ::launch

    /**
     * Runs the commands of a reveal in order until one succeeds (see [launchFirstSuccess]), so off the JavaFX thread;
     * the failure of the last one, or null. Tests replace it.
     */
    var launchFirst: (List<List<String>>) -> IOException? = ::launchFirstSuccess

    /** The label of the item: that of revealing a file if [reveal], else of opening a folder. */
    fun label(reveal: Boolean): String = if (reveal) fileManager.revealLabel else fileManager.label

    /** Opens the folder [target], the project folder for the empty area, in the file manager; reveals a file instead. */
    fun open(target: Path?) {
        val path = target ?: projectFolder() ?: return
        if (target != null && Files.isRegularFile(path)) return reveal(path)
        try {
            launchCommand(fileManager.command(path))
        } catch (e: IOException) {
            showError("Could not open $path in ${fileManager.name}: ${e.message}")
        }
    }

    private fun reveal(file: Path) {
        val commands = fileManager.reveal(file)
        val fail = { e: IOException -> showError("Could not open $file in ${fileManager.name}: ${e.message}") }
        if (commands.size == 1) {
            // Finder and Explorer return at once (and Explorer exits with 1 on success): start, do not wait.
            try {
                launchCommand(commands.single())
            } catch (e: IOException) {
                fail(e)
            }
        } else {
            runInBackground { launchFirst(commands)?.let { e -> dispatch { fail(e) } } }
        }
    }

    /** Opens a terminal in the folder [target], in the folder of a file, or in the project folder for the empty area. */
    fun openTerminal(target: Path?) {
        val path = target ?: projectFolder() ?: return
        openInTerminal()?.invoke(if (Files.isRegularFile(path)) path.parent else path)
    }
}
