package dev.sluide.fileviewers

import javafx.scene.Node
import javafx.scene.control.TextArea
import javafx.scene.text.Font
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/** Fallback viewer: shows any text file as plain, read-only text. Always asked last by [FileViewers]. */
@Component
class DefaultTextFileViewer : FileViewer {
    override fun canOpen(file: Path): Boolean = Files.isReadable(file) && isTextFile(file)

    override fun open(file: Path): Node =
        TextArea(file.readText()).apply {
            isEditable = false
            font = Font.font("Monospaced", font.size)
        }
}
