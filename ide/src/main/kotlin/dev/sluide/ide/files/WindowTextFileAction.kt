package dev.sluide.ide.files

import dev.sluide.fileexplorer.FileAction
import dev.sluide.speech.ReadAloud
import dev.sluide.translation.Translate
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.stage.Window
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignT
import org.kordamp.ikonli.materialdesign2.MaterialDesignV
import java.nio.file.Path

/** The answer to the question asked while a file has unsaved changes. */
internal enum class UnsavedChoice { SAVE_AND_USE, USE_SAVED, CANCEL }

/** Whether the explorer offers a text tool for [file]: a `.txt`, `.md` or `.markdown` file. */
internal fun isTextFile(file: Path): Boolean {
    val name = file.fileName?.toString()?.lowercase() ?: return false
    return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown")
}

/** How a tool names its explorer action and the question about unsaved changes. */
internal class TextToolLabels(
    /** The context-menu label and the title of the question. */
    val action: String,
    val saveButton: String,
    val savedButton: String,
    val icon: () -> Node,
) {
    companion object {
        val READ_ALOUD =
            TextToolLabels(
                "Read Aloud",
                "Save and Read Aloud",
                "Read Saved Version",
            ) { FontIcon(MaterialDesignV.VOLUME_HIGH) }
        val TRANSLATE =
            TextToolLabels(
                "Translate",
                "Save and Translate",
                "Translate Saved Version",
            ) { FontIcon(MaterialDesignT.TRANSLATE) }
    }
}

/**
 * An explorer action of a tool that takes a `.txt`, `.md` or `.markdown` file (Read Aloud, ADR 0124; Translate,
 * ADR 0126): hands the saved file to the tool. When a tab of the file has unsaved changes, it asks first (ADR 0089).
 */
internal class WindowTextFileAction(
    labels: TextToolLabels,
    /** The tool's `readFile`: reads the saved file off the FX thread and reports a failure through the callback. */
    private val readFile: (Path, (String) -> Unit) -> Unit,
    /** Whether a tab of the file has unsaved changes. */
    private val isDirty: (Path) -> Boolean,
    /** Saves the file's tab (`EditorArea.save`); `false` if it could not be saved. */
    private val save: (Path) -> Boolean,
    /** Asks what to do with the unsaved changes of the file; tests replace it, since it blocks. */
    private val ask: (Path) -> UnsavedChoice,
    private val showError: (String) -> Unit,
) {
    /** The action in the explorer's context menu, for one text file. */
    val action = FileAction(labels.action, icon = labels.icon, appliesTo = ::isTextFile, perform = ::perform)

    internal fun perform(file: Path) {
        if (isDirty(file)) {
            when (ask(file)) {
                UnsavedChoice.CANCEL -> return
                UnsavedChoice.SAVE_AND_USE -> if (!save(file)) return
                UnsavedChoice.USE_SAVED -> Unit
            }
        }
        readFile(file, showError)
    }
}

/** Asks in a dialog owned by [owner] what to do with the unsaved changes of [file] and waits; Cancel on dismissal. */
internal fun askUnsaved(
    owner: Window?,
    labels: TextToolLabels,
    file: Path,
): UnsavedChoice {
    val save = ButtonType(labels.saveButton, ButtonBar.ButtonData.OK_DONE)
    val saved = ButtonType(labels.savedButton, ButtonBar.ButtonData.OTHER)
    val answer =
        Alert(Alert.AlertType.CONFIRMATION, "${file.fileName} has unsaved changes.", save, saved, ButtonType.CANCEL)
            .apply {
                initOwner(owner)
                title = labels.action
                headerText = null
            }.showAndWait()
            .orElse(ButtonType.CANCEL)
    return when (answer) {
        save -> UnsavedChoice.SAVE_AND_USE
        saved -> UnsavedChoice.USE_SAVED
        else -> UnsavedChoice.CANCEL
    }
}

/** The explorer's actions of the tools that take text files: Read Aloud and Translate. */
internal class WindowTextFileActions(
    readAloud: ReadAloud,
    translate: Translate,
    private val isDirty: (Path) -> Boolean,
    private val save: (Path) -> Boolean,
    private val ask: (TextToolLabels, Path) -> UnsavedChoice,
    private val showError: (String) -> Unit,
) {
    /** Read Aloud and Translate, in this order. */
    val actions =
        listOf(
            action(TextToolLabels.READ_ALOUD, readAloud::readFile),
            action(TextToolLabels.TRANSLATE, translate::readFile),
        )

    private fun action(
        labels: TextToolLabels,
        readFile: (Path, (String) -> Unit) -> Unit,
    ) = WindowTextFileAction(labels, readFile, isDirty, save, { ask(labels, it) }, showError).action
}
