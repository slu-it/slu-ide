package dev.sluide.fileviewers

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.diff.CurrentText
import dev.sluide.fileviewers.diff.SideBySideDiffView
import dev.sluide.fileviewers.image.ImageFileViewer
import dev.sluide.fileviewers.preview.PreviewFileViewer
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
     * pattern; a [NamedFileViewer] listing the file's name counts as an exact name, other viewers than
     * [CodeFileViewer]s as matching the extension), the first of equals; for a text file without an extension, else
     * the first [ShebangViewer] that opens the interpreter its shebang names (the file is read once for this); else
     * [DefaultTextFileViewer] if it can. A viewer that opens the file by its name thus always wins over one that would
     * open it by its shebang.
     */
    fun viewerFor(file: Path): FileViewer? {
        specific
            .filter { it.canOpen(file) }
            .minByOrNull { viewer ->
                when {
                    viewer is NamedFileViewer && viewer.names(file) -> NameMatch.NAME
                    viewer is CodeFileViewer -> viewer.nameMatch(file) ?: NameMatch.EXTENSION
                    else -> NameMatch.EXTENSION
                }
            }?.let { return it }
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
        return SideBySideDiffView(CurrentText.File(file), highlighterOf(viewer), diff)
    }

    /**
     * Shows [current], the text of a file named [name], side by side with the original of [diff], e.g. a file as a
     * commit left it (ADR 0038). Read-only; the right side is [current], not the file. The highlighter is picked by the
     * name alone, as [viewerFor] would pick the viewer by name, without touching the disk: a name without an extension
     * that no viewer takes is looked at for a shebang in [current]. `null` if the viewer for the name does not show
     * text, e.g. for images.
     */
    fun openDiff(
        name: Path,
        current: String,
        diff: TextDiff,
    ): DiffFileView? {
        val viewer = viewerByName(name, current).takeIf(::showsDiffs) ?: return null
        return SideBySideDiffView(CurrentText.Fixed(current), highlighterOf(viewer), diff)
    }

    private fun highlighterOf(viewer: FileViewer): SyntaxHighlighter? =
        when (viewer) {
            is CodeFileViewer -> viewer.highlighter
            is SourceFileViewer -> viewer.sourceHighlighter
            else -> null
        }

    /**
     * The viewer [viewerFor] would pick for a file named [name] with the content [text], told without the file: by
     * the name as far as viewers tell it without reading the file (named, code, preview and image viewers), then by a
     * shebang in [text] for names without an extension, else [DefaultTextFileViewer].
     */
    internal fun viewerByName(
        name: Path,
        text: String,
    ): FileViewer {
        specific
            .mapNotNull { viewer -> nameMatch(viewer, name)?.let { viewer to it } }
            .minByOrNull { it.second }
            ?.let { return it.first }
        if (name.extension.isNotEmpty()) return fallback
        val interpreter = shebangInterpreter(text.lineSequence().first()) ?: return fallback
        return shebangViewers.firstOrNull { it.opensInterpreter(interpreter) } ?: fallback
    }

    /** How [name] matches [viewer] without reading the file; `null` if it does not, or the viewer cannot tell. */
    private fun nameMatch(
        viewer: FileViewer,
        name: Path,
    ): NameMatch? =
        when {
            viewer is NamedFileViewer && viewer.names(name) -> NameMatch.NAME
            viewer is CodeFileViewer -> viewer.nameMatch(name)
            viewer is PreviewFileViewer -> NameMatch.EXTENSION.takeIf { viewer.matchesExtension(name) }
            viewer is ImageFileViewer -> NameMatch.EXTENSION.takeIf { ImageFileViewer.isImageName(name) }
            else -> null
        }

    /** Whether the name of [file] is one of the [NamedFileViewer.fileNames], ignoring case. */
    private fun NamedFileViewer.names(file: Path): Boolean {
        val name = file.fileName?.toString() ?: return false
        return fileNames.any { it.equals(name, ignoreCase = true) }
    }

    /** Whether files shown by [viewer] have diffs ([openDiff]) and change markers: those shown as text. */
    fun showsDiffs(viewer: FileViewer): Boolean =
        viewer is CodeFileViewer ||
            viewer is DefaultTextFileViewer ||
            viewer is SourceFileViewer
}
