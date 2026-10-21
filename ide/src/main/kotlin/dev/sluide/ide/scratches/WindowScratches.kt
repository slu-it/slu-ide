package dev.sluide.ide.scratches

import dev.sluide.editorarea.EditorArea
import dev.sluide.fileexplorer.ExplorerRoot
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileexplorer.FolderIcons
import dev.sluide.fileicons.FileIcon
import dev.sluide.scratches.ScratchFiles
import dev.sluide.scratches.ScratchType
import dev.sluide.scratches.ScratchTypePicker
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.stage.Stage
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

private val log = System.getLogger(WindowScratches::class.java.name)

/** The label of the scratches folder's node in the file explorer. */
internal const val SCRATCHES_LABEL = "Scratches"

/**
 * The scratch files in one window (ADR 0066): the shared scratches folder as a second top-level node of the
 * [fileExplorer], File → New Scratch File… with its type picker, and the deletion of empty scratch files when their
 * last tab closes, unless another window has them open ([isOpenElsewhere]). Used on the JavaFX thread.
 */
internal class WindowScratches(
    private val scratchFiles: ScratchFiles,
    private val fileExplorer: FileExplorerView,
    private val editorArea: EditorArea,
    private val isOpenElsewhere: (Path) -> Boolean,
    private val showError: (Stage, String) -> Unit,
) {
    /** The window's type picker, made when it is first shown. */
    private val picker by lazy { ScratchTypePicker() }

    /**
     * Asks for the type of a new scratch file, with the last one chosen preselected, and passes the choice on; nothing
     * if the user closes the picker. Shows the type picker; tests replace it.
     */
    internal var askType: (Stage, (ScratchType) -> Unit) -> Unit = { stage, onPick ->
        picker.show(stage, scratchFiles.lastType, onPick)
    }

    /** The extra top-level nodes of the explorer: the scratches folder, created if missing; none if that fails. */
    fun roots(): List<ExplorerRoot> =
        try {
            scratchFiles.ensureFolder()
            listOf(
                ExplorerRoot(
                    scratchFiles.folder,
                    SCRATCHES_LABEL,
                    FolderIcons(FileIcon.SCRATCHES, FileIcon.SCRATCH_FOLDER, FileIcon.SCRATCH_FOLDER_OPEN),
                ),
            )
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot create the scratches folder ${scratchFiles.folder}", e)
            emptyList()
        }

    /** Whether [changed] names only scratch files or the scratches folder; `false` for none (a manual refresh). */
    fun onlyScratches(changed: Collection<Path>): Boolean =
        changed.isNotEmpty() && changed.all { scratchFiles.contains(it) || it == scratchFiles.folder }

    /**
     * File → New Scratch File… on Shortcut+Shift+N, IntelliJ's key on macOS; Ctrl+Shift+letter is not sent to the
     * shell, so it works from a terminal too.
     */
    fun menuItem(stage: Stage): MenuItem =
        MenuItem("New Scratch File…").apply {
            accelerator = KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
            setOnAction { newScratchFile(stage) }
        }

    /** Asks for the type, creates the file, shows it in the explorer and opens it in the editor with the focus. */
    fun newScratchFile(stage: Stage) {
        askType(stage) { type ->
            val path =
                try {
                    scratchFiles.create(type)
                } catch (e: IOException) {
                    log.log(System.Logger.Level.WARNING, "Cannot create a scratch file in ${scratchFiles.folder}", e)
                    showError(stage, "The scratch file cannot be created. ${reason(e)}")
                    return@askType
                }
            fileExplorer.reveal(path)
            editorArea.open(path)
        }
    }

    /**
     * For [EditorArea.onFileClosed]: deletes [path] if it is an empty scratch file that no tab shows, here or in
     * another window, also not under another name for the same file (another case after a case-only rename on a
     * case-insensitive file system, a symbolic link).
     */
    fun fileClosed(path: Path) {
        if (!scratchFiles.contains(path)) return
        if (editorArea.openPaths.any { isSameFile(it, path) } || isOpenElsewhere(path)) return
        scratchFiles.deleteIfEmpty(path)
    }
}

/** Whether [a] and [b] are the same file on disk; `false` if that cannot be told, e.g. one of them is gone. */
internal fun isSameFile(
    a: Path,
    b: Path,
): Boolean =
    try {
        Files.isSameFile(a, b)
    } catch (_: IOException) {
        false
    }

/** Why a scratch file could not be created, as a sentence for the error message. */
private fun reason(e: IOException): String =
    when (e) {
        is AccessDeniedException -> "Access denied."
        is FileAlreadyExistsException -> "${e.file} is in the way."
        is FileSystemException -> e.reason ?: e.toString()
        else -> e.message ?: e.toString()
    }
