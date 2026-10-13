package dev.sluide.fileviewers

import javafx.scene.Node
import javafx.scene.control.TextArea
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/** Fallback viewer: shows any text file as plain, read-only text. Always asked last by [FileViewers]. */
@Component
class DefaultTextFileViewer : FileViewer {
    override fun canOpen(file: Path): Boolean = Files.isReadable(file) && isTextFile(file)

    override fun open(file: Path): FileView = TextFileView(file)

    private class TextFileView(
        private val file: Path,
    ) : FileView {
        private val area =
            TextArea(file.readText()).apply { isEditable = false }

        override val node: Node get() = area

        override fun reload() {
            val text = file.readText()
            if (text == area.text) return
            val caret = area.caretPosition
            val scrollTop = area.scrollTop
            val scrollLeft = area.scrollLeft
            area.text = text
            area.positionCaret(caret.coerceAtMost(text.length))
            area.scrollTop = scrollTop
            area.scrollLeft = scrollLeft
        }
    }
}
