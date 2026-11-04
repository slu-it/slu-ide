package dev.sluide.ide.speech

import dev.sluide.fileexplorer.FileAction
import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.stage.Window
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignV
import java.nio.file.Path

/** The answer to the question asked while a file has unsaved changes. */
internal enum class UnsavedReadChoice { SAVE_AND_READ, READ_SAVED, CANCEL }

/** Whether the explorer offers Read Aloud for [file]: a `.txt`, `.md` or `.markdown` file. */
internal fun canReadAloud(file: Path): Boolean {
    val name = file.fileName?.toString()?.lowercase() ?: return false
    return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown")
}

/**
 * The explorer's Read Aloud for `.txt`, `.md` and `.markdown` files (ADR 0124): reads the saved file in the Read Aloud tab. When
 * a tab of the file has unsaved changes, it asks first (ADR 0089).
 */
internal class WindowReadAloud(
    /** `ReadAloud.readFile`: reads the saved file off the FX thread and reports a failure through the callback. */
    private val readFile: (Path, (String) -> Unit) -> Unit,
    /** Whether a tab of the file has unsaved changes. */
    private val isDirty: (Path) -> Boolean,
    /** Saves the file's tab (`EditorArea.save`); `false` if it could not be saved. */
    private val save: (Path) -> Boolean,
    /** Asks what to do with the unsaved changes of the file; tests replace it, since it blocks. */
    private val ask: (Path) -> UnsavedReadChoice,
    private val showError: (String) -> Unit,
) {
    /** Read Aloud in the explorer's context menu, for one `.txt` or `.md` file. */
    val action =
        FileAction(
            "Read Aloud",
            icon = { FontIcon(MaterialDesignV.VOLUME_HIGH) },
            appliesTo = ::canReadAloud,
            perform = ::perform,
        )

    internal fun perform(file: Path) {
        if (isDirty(file)) {
            when (ask(file)) {
                UnsavedReadChoice.CANCEL -> return
                UnsavedReadChoice.SAVE_AND_READ -> if (!save(file)) return
                UnsavedReadChoice.READ_SAVED -> Unit
            }
        }
        readFile(file, showError)
    }
}

private val SAVE_AND_READ = ButtonType("Save and Read Aloud", ButtonBar.ButtonData.OK_DONE)
private val READ_SAVED = ButtonType("Read Saved Version", ButtonBar.ButtonData.OTHER)

/** Asks in a dialog owned by [owner] what to do with the unsaved changes of [file] and waits; Cancel on dismissal. */
internal fun askUnsavedRead(
    owner: Window?,
    file: Path,
): UnsavedReadChoice {
    val answer =
        Alert(
            Alert.AlertType.CONFIRMATION,
            "${file.fileName} has unsaved changes.",
            SAVE_AND_READ,
            READ_SAVED,
            ButtonType.CANCEL,
        ).apply {
            initOwner(owner)
            title = "Read Aloud"
            headerText = null
        }.showAndWait()
            .orElse(ButtonType.CANCEL)
    return when (answer) {
        SAVE_AND_READ -> UnsavedReadChoice.SAVE_AND_READ
        READ_SAVED -> UnsavedReadChoice.READ_SAVED
        else -> UnsavedReadChoice.CANCEL
    }
}
