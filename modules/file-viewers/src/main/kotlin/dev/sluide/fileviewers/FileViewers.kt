package dev.sluide.fileviewers

import org.springframework.stereotype.Component
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
}
