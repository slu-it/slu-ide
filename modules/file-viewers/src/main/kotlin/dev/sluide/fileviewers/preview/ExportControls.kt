package dev.sluide.fileviewers.preview

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.stage.Window
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignE
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists

private val log = System.getLogger(ExportControls::class.java.name)

/**
 * The Export menu of a preview's toolbar: each of [exports] is an item that writes its export of the saved text beside
 * the file. An existing target is only replaced after asking. The export is written off the FX thread (it may download
 * images or render offscreen); the button is disabled meanwhile. Problems (an error, or what the export lacks, such as
 * images not embedded) are reported in a dialog; a clean export is not.
 * Everything but the writing runs on the FX thread.
 *
 * The writing is a task of the window's [tasks] (ADR 0061), "Exporting <file> as <item>", started on its thread; it
 * fails with the reason of a file or network problem, or the exception of anything else. Without a window's tasks
 * (tests), a failure is logged on its own.
 */
internal class ExportControls(
    private val exports: List<FileExport>,
    private val file: () -> Path,
    private val savedText: () -> String,
    private val owner: () -> Window?,
    private val tasks: () -> BackgroundTasks?,
) {
    val button =
        MenuButton(null, FontIcon(MaterialDesignE.EXPORT_VARIANT).apply { iconSize = ICON_SIZE }).apply {
            styleClass += "preview-export"
            tooltip = Tooltip("Export")
            // A click leaves the focus where it is.
            isFocusTraversable = false
            // Apart from the zoom controls or the table of contents button before it in the toolbar.
            HBox.setMargin(this, Insets(0.0, 0.0, 0.0, GAP))
            items += exports.map { item -> MenuItem(item.label).apply { setOnAction { export(item) } } }
        }

    /** Asks whether to replace the existing [target]; tests replace it, since it blocks. */
    var confirmOverwrite: (Path) -> Boolean = ::askToOverwrite

    /** Tells the user [message]; tests replace it, since it blocks. */
    var report: (String) -> Unit = ::showReport

    /** Writes [fileExport]'s export of the saved text, after asking if the target exists. */
    fun export(fileExport: FileExport) {
        val source = file()
        val target = fileExport.target(source)
        if (target.exists() && !confirmOverwrite(target)) return
        val text = savedText()
        val tasks = tasks()
        button.isDisable = true
        NamedThreads.virtual("sluide-export") {
            val task = tasks?.start("Exporting ${source.fileName} as ${fileExport.label}")
            var message: String? = null
            try {
                message =
                    try {
                        val problems = fileExport.write(source, text, target)
                        task?.close()
                        if (problems.isEmpty()) {
                            null
                        } else {
                            "Exported ${target.fileName}, with problems:\n" + problems.joinToString("\n")
                        }
                    } catch (e: Exception) {
                        val reason = e.message ?: e.javaClass.simpleName
                        // A file or network problem is expected; anything else is a bug.
                        when {
                            task == null -> log.log(level(e), "Export of $source to $target failed", e)
                            e is IOException -> task.failed(reason)
                            else -> task.failed(e)
                        }
                        "Could not export ${source.fileName}: $reason"
                    }
            } catch (t: Throwable) {
                task?.failed(t)
                throw t
            } finally {
                // Also after an error, so that the button never stays disabled.
                Platform.runLater {
                    button.isDisable = false
                    message?.let(report)
                }
            }
        }
    }

    private fun level(error: Exception) =
        if (error is IOException) System.Logger.Level.WARNING else System.Logger.Level.ERROR

    private fun askToOverwrite(target: Path): Boolean {
        val name = target.fileName
        val replace = ButtonType("Replace", ButtonBar.ButtonData.OK_DONE)
        val alert =
            Alert(
                Alert.AlertType.WARNING,
                "$name already exists in this folder. Replace it with the export?",
                replace,
                ButtonType.CANCEL,
            ).apply {
                owner()?.let(::initOwner)
                title = "Export"
                headerText = "Replace $name?"
                val cancel = dialogPane.lookupButton(ButtonType.CANCEL) as Button
                (dialogPane.lookupButton(replace) as Button).isDefaultButton = false
                cancel.isDefaultButton = true
                // Later, since the dialog focuses a button of its own once shown.
                setOnShown { Platform.runLater(cancel::requestFocus) }
            }
        return alert.showAndWait().orElse(ButtonType.CANCEL) == replace
    }

    private fun showReport(message: String) {
        Alert(Alert.AlertType.WARNING, message, ButtonType.OK)
            .apply {
                owner()?.let(::initOwner)
                title = "Export"
                headerText = null
            }.show()
    }

    private companion object {
        const val ICON_SIZE = 14
        const val GAP = 8.0
    }
}
