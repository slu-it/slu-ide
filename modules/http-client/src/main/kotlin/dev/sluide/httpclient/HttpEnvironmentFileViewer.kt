package dev.sluide.httpclient

import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.NamedFileViewer
import dev.sluide.fileviewers.SourceFileViewer
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.isTextFile
import dev.sluide.httpclient.env.EnvironmentFiles
import dev.sluide.httpclient.envedit.EnvironmentFileView
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shows the HTTP client's environment files as JSON beside a form of both files merged ([EnvironmentFileView],
 * ADR 0057). Claims their names, so they are not shown as plain JSON.
 */
@Component
class HttpEnvironmentFileViewer :
    SourceFileViewer,
    NamedFileViewer {
    override val fileNames: Set<String> = setOf(EnvironmentFiles.PUBLIC, EnvironmentFiles.PRIVATE)

    override val sourceHighlighter: SyntaxHighlighter? = Highlighters.forLanguage("json")

    override fun canOpen(file: Path): Boolean {
        val name = file.fileName?.toString() ?: return false
        return fileNames.any { it.equals(name, ignoreCase = true) } && Files.isReadable(file) && isTextFile(file)
    }

    override fun open(file: Path): FileView = EnvironmentFileView(file, CodeEditors.open(file, sourceHighlighter))
}
