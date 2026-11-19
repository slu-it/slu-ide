package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.tree.FileTreeItem
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val log = System.getLogger("dev.sluide.fileexplorer.actions.PastedFiles")

private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

/** The name offered for pasted clipboard content, without extension: `pasted-` and the local time of [clock]. */
internal fun pastedBaseName(clock: Clock): String = "pasted-${STAMP.format(LocalDateTime.now(clock))}"

/** Creates the new file for writing, failing if it exists; the default of the pastes, which tests replace. */
internal fun createNewFile(file: Path): OutputStream =
    Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

/** Whether [typed] names a folder: ends with `/`, on Windows also `\`, as [FileOperations.pathProblem] reads it. */
internal fun endsWithSeparator(typed: String) = typed.endsWith("/") || (IS_WINDOWS && typed.endsWith("\\"))

/**
 * The problem with the name [typed] by the user, whose final name is [finalName]; a blank name or a folder path is
 * judged as typed.
 */
internal fun pastedNameProblem(
    typed: String,
    finalName: String,
    folder: Path,
): String? =
    when {
        endsWithSeparator(typed) -> "The name must not end with /."
        typed.isBlank() -> FileOperations.pathProblem(typed, folder)
        else -> FileOperations.pathProblem(finalName, folder)
    }

/**
 * Writes what [bytes] gives as the new file [name] in [target], the path of [folder], in the calling (background)
 * thread, then shows the outcome with [ExplorerEditHost.dispatch]: an error, or the file selected and opened if
 * [folder] is still shown. Missing folders of [name] are created, the file is made by [create] (which fails if it
 * exists), and a failed write removes it again. [subject] ("image", "text") is for the log. Anything unexpected is
 * shown as "Could not save …" and then thrown on, never silent.
 */
internal fun writePastedFile(
    host: ExplorerEditHost,
    create: (Path) -> OutputStream,
    folder: FileTreeItem,
    target: Path,
    name: String,
    subject: String,
    bytes: () -> ByteArray,
) {
    var created: Path? = null
    var failure: String? = null
    try {
        val content = bytes()
        val file = FileOperations.createParents(target, name)
        try {
            create(file).use {
                // The file exists from here on; it is removed again if writing fails.
                created = file
                it.write(content)
            }
        } catch (_: FileAlreadyExistsException) {
            failure = "“$name” already exists in this folder."
        } catch (e: Throwable) {
            // Also after an unexpected failure: no partial file stays.
            created?.let(::deleteQuietly)
            created = null
            throw e
        }
    } catch (e: FileOperationException) {
        failure = e.message.orEmpty()
    } catch (e: IOException) {
        log.log(System.Logger.Level.WARNING, "Cannot save the clipboard $subject as $name in $target", e)
        failure = "Could not save “$name”: ${e.message ?: e::class.simpleName}"
    } finally {
        // Runs also when something unexpected escapes (e.g. an Error loading AWT's natives, which the background
        // thread logs): without a file and without a failure of its own, that is shown too, never silent.
        showOutcome(host, folder, name, created, failure)
    }
}

private fun deleteQuietly(file: Path) {
    try {
        Files.deleteIfExists(file)
    } catch (_: Throwable) {
        // Whatever fails here, the write's own failure is the one to show.
    }
}

/** Shows [failure] on the JavaFX thread; without one and without a [file] that it is "Could not save", else selects and opens [file]. */
private fun showOutcome(
    host: ExplorerEditHost,
    folder: FileTreeItem,
    name: String,
    file: Path?,
    failure: String?,
) {
    val outcome = failure ?: if (file == null) "Could not save “$name”." else null
    host.dispatch {
        if (outcome != null) {
            host.showError(outcome)
        } else if (file != null && host.isShown(folder)) {
            host.select(folder, file)
            host.requestOpen(file)
        }
    }
}
