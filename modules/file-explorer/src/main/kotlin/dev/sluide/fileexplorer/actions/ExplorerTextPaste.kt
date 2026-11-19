package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.texttypes.TextTypeDetector
import java.io.OutputStream
import java.nio.file.Path
import java.time.Clock

/**
 * Pastes text from the clipboard as a new file into a folder (ADR 0188): detects the type of the text in the
 * background, asks for a name and a type, writes the file in the background ([writePastedFile]) and shows it. Used on
 * the JavaFX thread.
 */
internal class ExplorerTextPaste(
    private val host: ExplorerEditHost,
    /** The detector, read when a paste runs: `FileExplorerView.textTypes` is set later than the paster is built. */
    private val detector: () -> TextTypeDetector,
    private val clock: Clock,
    /** Creates the new file for writing, failing if it exists; tests replace it by a stream that fails. */
    private val create: (Path) -> OutputStream = ::createNewFile,
) {
    /** Pastes [text], read from the clipboard on the JavaFX thread, into [folder]. */
    fun paste(
        folder: FileTreeItem,
        text: String,
    ) {
        // Read here, not in the background: the tree item and the detector belong to the JavaFX thread.
        val target = folder.value
        val types = detector()
        host.runInBackground {
            val detected = types.detect(text)
            host.dispatch {
                val name = host.askTextName(pastedBaseName(clock), detected, target)
                if (name != null) {
                    host.runInBackground {
                        writePastedFile(host, create, folder, target, name, "text") { text.toByteArray(Charsets.UTF_8) }
                    }
                }
            }
        }
    }
}
