package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.ClipboardImage
import dev.sluide.fileexplorer.ExplorerClipboard
import dev.sluide.fileexplorer.tree.FileTreeItem
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock

private val log = System.getLogger(ExplorerImagePaste::class.java.name)

/**
 * Pastes an image from the clipboard as a PNG file into a folder (ADR 0167): reads its pixels, asks for a name, writes
 * the file in the background and shows it. Used on the JavaFX thread.
 */
internal class ExplorerImagePaste(
    private val host: ExplorerEditHost,
    private val clipboard: () -> ExplorerClipboard,
    private val clock: Clock,
) {
    /** Pastes the clipboard image into [folder]; shows an error if it cannot be read. */
    fun paste(folder: FileTreeItem) {
        val image =
            try {
                clipboard().image()
            } catch (e: IOException) {
                host.showError(e.message.orEmpty())
                return
            } ?: return
        // Read here, not in the background: the tree item belongs to the JavaFX thread.
        val target = folder.value
        val typed = host.askImageName(defaultImageName(clock), { problem(it, target) }, ::note) ?: return
        val name = withPngExtension(typed)
        host.runInBackground { write(image, folder, target, name) }
    }

    /** The problem with the typed name as the final name; a blank name or a folder path is judged as typed. */
    private fun problem(
        typed: String,
        folder: Path,
    ): String? =
        when {
            endsWithSeparator(typed) -> "The name must not end with /."
            typed.isBlank() -> FileOperations.pathProblem(typed, folder)
            else -> FileOperations.pathProblem(withPngExtension(typed), folder)
        }

    /** Whether [typed] names a folder: ends with `/`, on Windows also `\`, as [FileOperations.pathProblem] reads it. */
    private fun endsWithSeparator(typed: String) = typed.endsWith("/") || (IS_WINDOWS && typed.endsWith("\\"))

    private fun note(typed: String): String? {
        if (typed.isBlank() || endsWithSeparator(typed)) return null
        return withPngExtension(typed).takeIf { it != typed }?.let { "Saved as “$it”." }
    }

    private fun write(
        image: ClipboardImage,
        folder: FileTreeItem,
        target: Path,
        name: String,
    ) {
        var created: Path? = null
        var failure: String? = null
        try {
            val bytes = pngBytes(image)
            val file = FileOperations.createParents(target, name)
            try {
                Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                    // The file exists from here on; it is removed again if writing fails.
                    created = file
                    it.write(bytes)
                }
            } catch (_: FileAlreadyExistsException) {
                failure = "“$name” already exists in this folder."
            } catch (e: Throwable) {
                // Also after an unexpected failure: no partial PNG stays.
                created?.let { runCatching { Files.deleteIfExists(it) } }
                created = null
                throw e
            }
        } catch (e: FileOperationException) {
            failure = e.message.orEmpty()
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Cannot save the clipboard image as $name in $target", e)
            failure = "Could not save “$name”: ${e.message ?: e::class.simpleName}"
        } finally {
            // Runs also when something unexpected escapes (e.g. an Error loading AWT's natives, which the background
            // thread logs): without a file and without a failure of its own, that is shown too, never silent.
            val file = created
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
    }
}
