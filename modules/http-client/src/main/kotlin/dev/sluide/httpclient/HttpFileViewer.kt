package dev.sluide.httpclient

import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.SourceFileViewer
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.isTextFile
import dev.sluide.httpclient.ui.HttpFileView
import dev.sluide.httpclient.ui.HttpSyntaxHighlighter
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/** Shows `.http` and `.rest` files as editable, highlighted requests that can be run ([HttpFileView]). */
@Component
class HttpFileViewer : SourceFileViewer {
    override val sourceHighlighter: SyntaxHighlighter = HttpSyntaxHighlighter()

    override fun canOpen(file: Path): Boolean = isHttpFile(file) && Files.isReadable(file) && isTextFile(file)

    // `#` comments, as IntelliJ comments out lines of .http files; `//` comments are read too.
    override fun open(file: Path): FileView =
        HttpFileView(file, CodeEditors.open(file, sourceHighlighter, lineComment = "#"))
}

private val HTTP_EXTENSIONS = setOf("http", "rest")

/** Whether [file] is a `.http` or `.rest` file by its name, ignoring case: what the HTTP client shows and completes. */
fun isHttpFile(file: Path): Boolean = file.extension.lowercase() in HTTP_EXTENSIONS
