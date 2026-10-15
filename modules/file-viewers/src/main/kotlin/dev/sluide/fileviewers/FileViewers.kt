package dev.sluide.fileviewers

import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * All available [FileViewer]s in the order they are asked: every specific viewer first, [DefaultTextFileViewer] last,
 * regardless of how Spring orders the beans.
 */
@Component
class FileViewers(
    viewers: List<FileViewer>,
    private val fallback: DefaultTextFileViewer,
) {
    private val specific = viewers.filterNot { it === fallback }
    private val shebangViewers = specific.filterIsInstance<ShebangViewer>()

    val viewers: List<FileViewer> = specific + fallback

    /**
     * The viewer to show [file] with, or `null` if none can, in which case the file is not opened: of the specific
     * viewers that can open it, the one its name matches best ([NameMatch]: exact name, then extension, then name
     * pattern; viewers other than [CodeFileViewer]s count as matching the extension), the first of equals; for a text
     * file without an extension, else the first [ShebangViewer] that opens the interpreter its shebang names (the file
     * is read once for this); else [DefaultTextFileViewer] if it can. A viewer that opens the file by its name thus
     * always wins over one that would open it by its shebang.
     */
    fun viewerFor(file: Path): FileViewer? {
        specific
            .filter { it.canOpen(file) }
            .minByOrNull { (it as? CodeFileViewer)?.nameMatch(file) ?: NameMatch.EXTENSION }
            ?.let { return it }
        if (file.extension.isNotEmpty() || shebangViewers.isEmpty()) return fallback.takeIf { it.canOpen(file) }
        // The fallback opens exactly the text files, which the sample already tells.
        val sample = readTextSample(file) ?: return null
        val interpreter = shebangInterpreter(firstLine(sample)) ?: return fallback
        return shebangViewers.firstOrNull { it.opensInterpreter(interpreter) } ?: fallback
    }

    /**
     * Shows [file] side by side with the original of [diff], highlighted like its code viewer would (ADR 0015).
     * `null` if no viewer can show the file or its viewer does not show plain or highlighted text.
     */
    @Throws(IOException::class)
    fun openDiff(
        file: Path,
        diff: TextDiff,
    ): DiffFileView? {
        val viewer = viewerFor(file)?.takeIf(::showsDiffs) ?: return null
        val highlighter =
            when (viewer) {
                is CodeFileViewer -> viewer.highlighter
                is PreviewFileViewer -> viewer.highlighter
                is SourceFileViewer -> viewer.sourceHighlighter
                else -> null
            }
        return SideBySideDiffView(file, highlighter, diff)
    }

    /** Whether files shown by [viewer] have diffs ([openDiff]) and change markers: those shown as text. */
    fun showsDiffs(viewer: FileViewer): Boolean =
        viewer is CodeFileViewer ||
            viewer is PreviewFileViewer ||
            viewer is DefaultTextFileViewer ||
            viewer is SourceFileViewer
}
