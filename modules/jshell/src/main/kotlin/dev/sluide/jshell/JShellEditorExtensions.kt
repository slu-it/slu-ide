package dev.sluide.jshell

import dev.sluide.fileviewers.CodeEditorExtension
import javafx.scene.control.MenuItem
import kotlin.io.path.extension

/** What the editors of other files get from JShell (code editor extensions, ADR 0108; ADR 0191). */
object JShellEditorExtensions {
    /**
     * Adds "Evaluate Selection in JShell" to the context menu of editors of `.java` files (the extension is asked on
     * each menu show, so a renamed file loses it); disabled without a selection. [open] gets the selected text.
     */
    fun evaluateSelection(open: (String) -> Unit): CodeEditorExtension =
        CodeEditorExtension { editor ->
            editor.addContextMenuItems { target ->
                val isJava =
                    editor.file.value.extension
                        .lowercase() == "java"
                if (!isJava) {
                    emptyList()
                } else {
                    listOf(
                        MenuItem("Evaluate Selection in JShell").apply {
                            isDisable = target.selectedText.isEmpty()
                            setOnAction { open(target.selectedText) }
                        },
                    )
                }
            }
            null
        }
}
