package dev.sluide.ide.help

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.diagnostics.Diagnostics
import dev.sluide.editorarea.EditorArea
import dev.sluide.fileexplorer.fileManagerName
import dev.sluide.fileexplorer.openInFileManager
import dev.sluide.ide.logging.currentLogFile
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.stage.Stage
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** What [dev.sluide.diagnostics.Diagnostics.capture] takes. */
internal typealias CaptureDiagnostics = (
    info: List<Pair<String, String>>,
    logFile: Path?,
    tasks: BackgroundTasks,
    done: (Path) -> Unit,
    failed: (Throwable) -> Unit,
) -> Unit

/**
 * The Help menu of one window: Capture Diagnostics, Reveal Diagnostics Folder and About Thoreau (ADR 0127). A capture
 * opens its report in a tab through [openReport]; [showError] tells why one failed. [capturing] is app-wide: the capture
 * item is disabled in every window while one runs. Used on the JavaFX thread.
 */
internal class WindowHelp(
    private val diagnosticsFolder: Path,
    private val capturing: ReadOnlyBooleanProperty,
    /** Starts a capture; tests replace it, since a real one samples for 10 seconds. */
    internal var capture: CaptureDiagnostics,
    private val tasks: BackgroundTasks,
    private val openReport: (Path) -> Unit,
    private val showError: (Stage, String) -> Unit,
) {
    private val log = System.getLogger(WindowHelp::class.java.name)

    private var disposed = false

    /** Opens a folder in the system's file manager; tests replace it. */
    internal var openFolder: (Path) -> Unit = ::openInFileManager

    /** Shows the About dialog; tests replace it, since it blocks. */
    internal var showAbout: (Stage) -> Unit = { showAboutDialog(it, aboutLines(diagnosticsFolder = diagnosticsFolder)) }

    /** The Help menu, to be the last one of the menu bar. */
    fun helpMenu(stage: Stage): Menu {
        val captureItem =
            MenuItem("Capture Diagnostics").apply {
                accelerator =
                    KeyCodeCombination(
                        KeyCode.D,
                        KeyCombination.SHORTCUT_DOWN,
                        KeyCombination.ALT_DOWN,
                        KeyCombination.SHIFT_DOWN,
                    )
                disableProperty().bind(capturing)
                setOnAction { captureDiagnostics(stage) }
            }
        val reveal = MenuItem("Reveal Diagnostics Folder").apply { setOnAction { revealFolder(stage) } }
        val about = MenuItem("About Thoreau").apply { setOnAction { showAbout(stage) } }
        return Menu("Help", null, captureItem, reveal, SeparatorMenuItem(), about)
    }

    /** The window is closed: a capture that ends later neither opens its report nor shows an error. */
    fun dispose() {
        disposed = true
    }

    private fun captureDiagnostics(stage: Stage) {
        capture(
            aboutLines(diagnosticsFolder = diagnosticsFolder),
            currentLogFile,
            tasks,
            { report ->
                if (disposed) {
                    log.log(System.Logger.Level.INFO, "Window closed during the capture, the report is at $report")
                } else {
                    openReport(report)
                }
            },
            { e ->
                if (!disposed) showError(stage, "Could not capture diagnostics: ${e.message ?: e.javaClass.simpleName}")
            },
        )
    }

    private fun createFolder() {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
            Files.createDirectories(diagnosticsFolder, ownerOnly)
        } else {
            Files.createDirectories(diagnosticsFolder)
        }
    }

    private fun revealFolder(stage: Stage) {
        try {
            createFolder()
            openFolder(diagnosticsFolder)
        } catch (e: IOException) {
            showError(stage, "Could not open $diagnosticsFolder in $fileManagerName: ${e.message}")
        }
    }
}

/** The Help menu of a window with the app's [diagnostics], whose reports open in a tab of [editorArea]. */
internal fun windowHelp(
    diagnostics: Diagnostics,
    tasks: BackgroundTasks,
    editorArea: EditorArea,
    showError: (Stage, String) -> Unit,
) = WindowHelp(
    diagnostics.folder,
    diagnostics.capturing,
    diagnostics::capture,
    tasks,
    { editorArea.open(it) },
    showError,
)
