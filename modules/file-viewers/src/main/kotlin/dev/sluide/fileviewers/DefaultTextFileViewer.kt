package dev.sluide.fileviewers

import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fallback viewer: shows any text file as plain text in an editable [CodeView] without highlighting. Always asked last
 * by [FileViewers].
 */
@Component
class DefaultTextFileViewer : FileViewer {
    override fun canOpen(file: Path): Boolean = Files.isReadable(file) && isTextFile(file)

    override fun open(file: Path): FileView = CodeView(file, null)
}
