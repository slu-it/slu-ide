package dev.sluide.fileviewers

import dev.sluide.fileviewers.code.CodeView
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fallback viewer: shows any text file as plain text in an editable [CodeView] without highlighting. Always asked last
 * by [FileViewers]. Shows content from elsewhere ([ContentFileViewer]) for `.txt` files.
 */
@Component
class DefaultTextFileViewer :
    FileViewer,
    ContentFileViewer {
    override fun canOpen(file: Path): Boolean = Files.isReadable(file) && isTextFile(file)

    override fun open(file: Path): FileView = CodeView(file, null, ownToolbar = true)

    override fun opensContent(name: String): Boolean = name.substringAfterLast('.', "").equals("txt", ignoreCase = true)

    override fun open(
        file: Path,
        content: FileContent,
    ): FileView = CodeView(file, null, ownToolbar = true, content = content)
}
