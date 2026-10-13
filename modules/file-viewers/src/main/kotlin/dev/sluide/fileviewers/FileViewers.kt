package dev.sluide.fileviewers

import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Path

/**
 * All available [FileViewer]s in the order they are asked: every specific viewer first, [DefaultTextFileViewer] last,
 * regardless of how Spring orders the beans.
 */
@Component
class FileViewers(
    viewers: List<FileViewer>,
    fallback: DefaultTextFileViewer,
) {
    val viewers: List<FileViewer> = viewers.filterNot { it === fallback } + fallback

    /** The first viewer that can show [file], or `null` if none can, in which case the file is not opened. */
    fun viewerFor(file: Path): FileViewer? = viewers.firstOrNull { it.canOpen(file) }

    /**
     * Shows [file] side by side with the original of [diff], highlighted like its code viewer would (ADR 0015).
     * `null` if no viewer can show the file or its viewer does not show plain or highlighted text.
     */
    @Throws(IOException::class)
    fun openDiff(
        file: Path,
        diff: TextDiff,
    ): DiffFileView? {
        val highlighter =
            when (val viewer = viewerFor(file)) {
                is CodeFileViewer -> viewer.highlighter
                is DefaultTextFileViewer -> null
                else -> return null
            }
        return SideBySideDiffView(file, highlighter, diff)
    }
}
