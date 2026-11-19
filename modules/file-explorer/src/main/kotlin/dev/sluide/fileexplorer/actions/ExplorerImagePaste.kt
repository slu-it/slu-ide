package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.ExplorerClipboard
import dev.sluide.fileexplorer.tree.FileTreeItem
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import java.time.Clock

/**
 * Pastes an image from the clipboard as a PNG file into a folder (ADR 0167): reads its pixels, asks for a name, writes
 * the file in the background ([writePastedFile]) and shows it. Used on the JavaFX thread.
 */
internal class ExplorerImagePaste(
    private val host: ExplorerEditHost,
    private val clipboard: () -> ExplorerClipboard,
    private val clock: Clock,
    /** Creates the new file for writing, failing if it exists; tests replace it by a stream that fails. */
    private val create: (Path) -> OutputStream = ::createNewFile,
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
        host.runInBackground { writePastedFile(host, create, folder, target, name, "image") { pngBytes(image) } }
    }

    private fun problem(
        typed: String,
        folder: Path,
    ): String? = pastedNameProblem(typed, withPngExtension(typed), folder)

    private fun note(typed: String): String? {
        if (typed.isBlank() || endsWithSeparator(typed)) return null
        return withPngExtension(typed).takeIf { it != typed }?.let { "Saved as “$it”." }
    }
}
