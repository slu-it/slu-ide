package dev.sluide.ide.conversions

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.converting.FileConversion
import dev.sluide.converting.TextFormat
import javafx.application.Platform
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.Executor

/**
 * The explorer's "Convert to" of this window (ADR 0115): converts a file with the Converting tool off the FX thread,
 * then writes the result beside it (asking before replacing a file), shows it in the explorer and opens it. What the
 * target could not keep ([showNotes]) and failures ([showError]) are told in dialogs. [executor] runs the conversion
 * and [dispatch] applies its result, the FX thread in the application.
 */
internal class WindowConversions(
    private val targets: (Path) -> List<TextFormat>,
    private val convert: (Path, TextFormat) -> FileConversion,
    private val reveal: (Path) -> Unit,
    private val open: (Path) -> Unit,
    private val askToOverwrite: (Path) -> Boolean,
    private val hasUnsavedChanges: (Path) -> Boolean,
    private val askToConvertSaved: (Path) -> Boolean,
    private val showError: (String) -> Unit,
    private val showNotes: (String) -> Unit,
    private val executor: Executor = NamedThreads.single("File conversion"),
    private val dispatch: (Runnable) -> Unit = Platform::runLater,
    private val siblings: (Path) -> List<String> = ::namesIn,
    private val write: (Path, String) -> Unit = ::writeAtomically,
) {
    private var disposed = false

    /** Drops the results of conversions still running, as the window closes. */
    fun dispose() {
        disposed = true
    }

    /** The names of the formats offered for [file]; none if it is not a file of a data format. */
    fun targetNames(file: Path): List<String> = targets(file).map(TextFormat::name)

    /** Converts [file] to the format named [format], one of [targetNames]. */
    fun convert(
        file: Path,
        format: String,
    ) {
        val target = targets(file).firstOrNull { it.name == format } ?: return
        // The conversion reads the file on disk, not the tab's text.
        if (hasUnsavedChanges(file) && !askToConvertSaved(file)) return
        executor.execute {
            val result = convert(file, target)
            dispatch(Runnable { finish(file, target, result) })
        }
    }

    private fun finish(
        source: Path,
        format: TextFormat,
        result: FileConversion,
    ) {
        if (disposed) return
        when (result) {
            is FileConversion.Failed -> showError(result.message)
            is FileConversion.Converted -> writeAndOpen(source, format, result)
        }
    }

    private fun writeAndOpen(
        source: Path,
        format: TextFormat,
        result: FileConversion.Converted,
    ) {
        val path = targetFile(source, format.extensions, siblings(source))
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return showError("A folder named ${path.fileName} is in the way")
        }
        // A dangling symbolic link counts as a file: replacing it is asked about as well.
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !askToOverwrite(path)) return
        try {
            write(path, result.text)
        } catch (e: IOException) {
            showError("“${path.fileName}” cannot be written: ${writeFailureReason(e)}")
            return
        }
        reveal(path)
        open(path)
        if (result.notes.isNotEmpty()) showNotes(notesMessage(source, format.name, path, result.notes))
    }
}

private fun namesIn(file: Path): List<String> =
    try {
        Files.list(file.parent).use { stream -> stream.map { it.fileName.toString() }.toList() }
    } catch (_: IOException) {
        emptyList()
    }
