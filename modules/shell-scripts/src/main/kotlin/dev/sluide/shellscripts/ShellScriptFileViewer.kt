package dev.sluide.shellscripts

import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.ExtensionFileViewer
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.RunnableFileViewer
import dev.sluide.fileviewers.ShebangViewer
import dev.sluide.fileviewers.SourceFileViewer
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.interpreterMatches
import dev.sluide.fileviewers.isTextFile
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Shows shell scripts as source that can be run, with their output beside it ([ShellScriptView], ADR 0089): files
 * with the extensions `.sh`, `.bash`, `.zsh` and `.ksh` ([canOpen]), and text files without an extension whose shebang
 * names `sh`, `bash`, `zsh`, `dash` or `ksh` (picked by [dev.sluide.fileviewers.FileViewers] through
 * [opensInterpreter]). Startup files such as `.bashrc` are not scripts to run; the plain shell viewer shows them.
 */
@Component
class ShellScriptFileViewer :
    SourceFileViewer,
    ExtensionFileViewer,
    ShebangViewer,
    RunnableFileViewer {
    override val fileExtensions: Set<String> = setOf("sh", "bash", "zsh", "ksh")

    override val sourceHighlighter: SyntaxHighlighter = Highlighters.shell

    /** Files with one of the [fileExtensions]; scripts without an extension are picked by their shebang instead. */
    override fun canOpen(file: Path): Boolean =
        file.extension.lowercase() in fileExtensions && Files.isReadable(file) && isTextFile(file)

    override fun opensInterpreter(interpreter: String): Boolean = interpreterMatches(interpreter, INTERPRETERS)

    override fun open(file: Path): FileView = ShellScriptView(file, CodeEditors.open(file, sourceHighlighter, "#"))

    private companion object {
        val INTERPRETERS = setOf("sh", "bash", "zsh", "dash", "ksh")
    }
}
