package dev.sluide.ide.scratches

import dev.sluide.editorarea.EditorArea
import dev.sluide.encryptedfiles.EncryptedFiles
import dev.sluide.encryptedfiles.Sluenc
import dev.sluide.encryptedfiles.newPasswordDialog
import dev.sluide.fileexplorer.ExplorerRoot
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileexplorer.FolderIcons
import dev.sluide.fileicons.FileIcon
import dev.sluide.scratches.GeneratorContext
import dev.sluide.scratches.GeneratorPicker
import dev.sluide.scratches.ScratchFiles
import dev.sluide.scratches.ScratchGenerator
import dev.sluide.scratches.ScratchType
import dev.sluide.scratches.ScratchTypePicker
import dev.sluide.scratches.safeBaseName
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.stage.Stage
import javafx.stage.Window
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
 * [fileExplorer], File → New Scratch File… with its type picker, File → New Generated Scratch File… with its generator
 * picker (ADR 0114), and the deletion of empty scratch files when their last tab closes, unless another window has
 * them open ([isOpenElsewhere]). The encrypted types ask for a password and open unlocked (ADR 0162); an encrypted
 * scratch is deleted only when its tab closes unlocked and empty. Used on the JavaFX thread.
 */
internal class WindowScratches(
    private val scratchFiles: ScratchFiles,
    private val fileExplorer: FileExplorerView,
    private val editorArea: EditorArea,
    private val encryptedFiles: EncryptedFiles,
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

    /** The window's generator picker, made when it is first shown. */
    private val generatorPicker by lazy { GeneratorPicker() }

    /**
     * Asks for the generator of a new scratch file, with the last one chosen preselected, and passes the choice on;
     * nothing if the user closes the picker. Shows the generator picker; tests replace it.
     */
    internal var askGenerator: (Stage, (ScratchGenerator) -> Unit) -> Unit = { stage, onPick ->
        generatorPicker.show(stage, scratchFiles.lastGenerator, onPick)
    }

    /**
     * Asks for the password of a new encrypted scratch file and runs the work with it, see [newPasswordDialog]; tests
     * replace it.
     */
    internal var askNewPassword: (Window?, String, (CharArray) -> Unit) -> Boolean = ::newPasswordDialog

    init {
        // An empty scratch file is deleted when its last tab in any window closes (ADR 0066).
        editorArea.onFileClosed = ::fileClosed
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
    fun onlyScratches(changed: Collection<Path>): Boolean = scratchFiles.onlyScratches(changed)

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
            if (type.encrypted) createEncrypted(stage, type) else createAndOpen(stage) { scratchFiles.create(type) }
        }
    }

    /** The two New Scratch File items, for the File menu. */
    fun menuItems(stage: Stage): List<MenuItem> = listOf(menuItem(stage), generatedMenuItem(stage))

    /**
     * Asks for a password, creates an empty encrypted scratch file, owner-only, with it on the dialog's background
     * thread and hands the key over there, so the registry's timeout owns it on every path; the tab then opens
     * unlocked. Nothing on Cancel. A failure shows in the dialog.
     */
    private fun createEncrypted(
        stage: Stage,
        type: ScratchType,
    ) {
        var created: Path? = null
        val done =
            askNewPassword(stage, "New Encrypted Scratch File") { password ->
                val made = Sluenc.create(password, ByteArray(0))
                try {
                    val file = scratchFiles.create("scratch", type.extension, made.bytes, ownerOnly = true)
                    encryptedFiles.handOver(file, made.key)
                    created = file
                } catch (e: Throwable) {
                    made.key.close()
                    throw e
                }
            }
        val path = created?.takeIf { done } ?: return
        scratchFiles.lastType = type
        fileExplorer.reveal(path)
        editorArea.open(path)
    }

    /** File → New Generated Scratch File… on Shortcut+Shift+G (ADR 0114); like [menuItem], it works from a terminal. */
    fun generatedMenuItem(stage: Stage): MenuItem =
        MenuItem("New Generated Scratch File…").apply {
            accelerator = KeyCodeCombination(KeyCode.G, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
            setOnAction { newGeneratedScratchFile(stage) }
        }

    /**
     * Asks for the generator, generates the content (on this thread, it is tiny), creates the file with it, remembers
     * the generator for the next ask, shows the file in the explorer and opens it in the editor with the focus. A
     * generator that throws gets an error message and no file.
     */
    fun newGeneratedScratchFile(stage: Stage) {
        askGenerator(stage) { generator ->
            val content =
                try {
                    generator.generate(GeneratorContext())
                } catch (e: RuntimeException) {
                    log.log(System.Logger.Level.ERROR, "The generator ${generator.name} failed", e)
                    showError(stage, "The content cannot be generated. ${e.message ?: e.toString()}")
                    return@askGenerator
                }
            createAndOpen(stage) {
                scratchFiles
                    .create(generator.baseName, generator.extension, content)
                    .also { scratchFiles.lastGenerator = generator }
            }
        }
    }

    /**
     * Creates the scratch file `<baseName>.<extension>` with [text] (Converting's "Open as Scratch", ADR 0115, and the
     * Databases tab's DDL and exports), shows it in the explorer and opens it in the editor; an error message if that
     * fails. [baseName] is cleaned by [safeBaseName].
     */
    fun openText(
        stage: Stage,
        baseName: String,
        extension: String,
        text: String,
    ) {
        createAndOpen(stage) { scratchFiles.create(safeBaseName(baseName), extension, text) }
    }

    /** Creates a scratch file by [create], reveals and opens it; an error message if that throws an IOException. */
    private fun createAndOpen(
        stage: Stage,
        create: () -> Path,
    ) {
        val path =
            try {
                create()
            } catch (e: IOException) {
                log.log(System.Logger.Level.WARNING, "Cannot create a scratch file in ${scratchFiles.folder}", e)
                showError(stage, "The scratch file cannot be created. ${reason(e)}")
                return
            }
        fileExplorer.reveal(path)
        editorArea.open(path)
    }

    /**
     * For [EditorArea.onFileClosed]: deletes [path] if it is an empty scratch file that no tab shows, here or in
     * another window, also not under another name for the same file (another case after a case-only rename on a
     * case-insensitive file system, a symbolic link). An encrypted scratch is deleted only if its tab closed unlocked
     * and empty ([EncryptedFiles.takeClosedEmpty]) and the file still holds the bytes it was closed with.
     */
    fun fileClosed(path: Path) {
        val emptyBytes = encryptedFiles.takeClosedEmpty(path)
        if (!scratchFiles.contains(path)) return
        if (editorArea.openPaths.any { isSameFile(it, path) } || isOpenElsewhere(path)) return
        if (emptyBytes == null) {
            scratchFiles.deleteIfEmpty(path)
        } else {
            scratchFiles.deleteIfEncryptedEmpty(path, emptyBytes)
        }
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
