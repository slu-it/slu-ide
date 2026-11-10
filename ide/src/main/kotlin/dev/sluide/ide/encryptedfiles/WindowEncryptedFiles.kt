package dev.sluide.ide.encryptedfiles

import dev.sluide.editorarea.EditorArea
import dev.sluide.encryptedfiles.EncryptedFiles
import dev.sluide.fileviewers.FileView
import javafx.beans.binding.Bindings
import javafx.beans.value.ObservableBooleanValue
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.stage.Stage

/**
 * The encrypted files in one window's File menu (ADR 0162): Lock Encrypted File for the current tab and Lock All
 * Encrypted Files for the tabs of all windows. Used on the JavaFX thread.
 */
internal class WindowEncryptedFiles(
    private val encryptedFiles: EncryptedFiles,
    private val editorArea: EditorArea,
) {
    /** Whether the current tab shows an unlocked encrypted file; follows tab changes and every lock-state change. */
    private val canLock: ObservableBooleanValue =
        Bindings.createBooleanBinding(
            { selectedView()?.let(encryptedFiles::isUnlockedEncrypted) == true },
            editorArea.selectedFile,
            encryptedFiles.revision,
        )

    /** The view of the current tab if it shows a file (the editor area exposes it by path only). */
    private fun selectedView(): FileView? = editorArea.selectedPath?.let(editorArea::viewOf)

    /**
     * A separator, File → Lock Encrypted File on Shortcut+Shift+L and Lock All Encrypted Files on
     * Shortcut+Alt+Shift+L; neither is sent to the shell, so both work from a terminal too. Disabled items do not
     * fire their accelerators.
     */
    fun menuItems(stage: Stage): List<MenuItem> {
        val lock =
            MenuItem("Lock Encrypted File").apply {
                accelerator = KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
                disableProperty().bind(canLock.map { !it })
                setOnAction { selectedView()?.let(encryptedFiles::lock) }
            }
        val lockAll =
            MenuItem("Lock All Encrypted Files").apply {
                accelerator =
                    KeyCodeCombination(
                        KeyCode.L,
                        KeyCombination.SHORTCUT_DOWN,
                        KeyCombination.ALT_DOWN,
                        KeyCombination.SHIFT_DOWN,
                    )
                disableProperty().bind(encryptedFiles.anyUnlocked.map { !it })
                setOnAction { encryptedFiles.lockAll(stage) }
            }
        return listOf(SeparatorMenuItem(), lock, lockAll)
    }
}
