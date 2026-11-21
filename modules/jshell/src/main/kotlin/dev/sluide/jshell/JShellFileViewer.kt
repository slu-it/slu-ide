package dev.sluide.jshell

import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.ExtensionFileViewer
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.SourceFileViewer
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.isTextFile
import dev.sluide.jshell.view.JShellView
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Shows JShell scripts (`.jsh`) as Java source whose snippets run in a live JShell, with the transcript beside it
 * ([JShellView], ADR 0191). It is not a `RunnableFileViewer`: the explorer offers no Run for them.
 */
@Component
class JShellFileViewer :
    SourceFileViewer,
    ExtensionFileViewer {
    override val fileExtensions: Set<String> = setOf("jsh")

    override val sourceHighlighter: SyntaxHighlighter = checkNotNull(Highlighters.forLanguage("java"))

    override fun canOpen(file: Path): Boolean =
        file.extension.lowercase() in fileExtensions && Files.isReadable(file) && isTextFile(file)

    override fun open(file: Path): FileView = JShellView(file, CodeEditors.open(file, sourceHighlighter, "//"))
}
