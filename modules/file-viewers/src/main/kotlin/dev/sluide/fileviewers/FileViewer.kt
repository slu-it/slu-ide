package dev.sluide.fileviewers

import javafx.scene.Node
import java.io.IOException
import java.nio.file.Path

/**
 * Shows the content of a file, read-only. Every Spring bean implementing this interface is asked by
 * [FileViewers] whether it can show a file; [DefaultTextFileViewer] is always asked last.
 */
interface FileViewer {
    /** Whether this viewer can show [file]. Must be cheap and must not throw. */
    fun canOpen(file: Path): Boolean

    /** Creates the view for [file], which [canOpen] accepted. */
    @Throws(IOException::class)
    fun open(file: Path): Node
}
