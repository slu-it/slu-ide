package dev.sluide.fileviewers.preview

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewer
import dev.sluide.fileviewers.FileViewers
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.nio.file.Path

/** The packages whose viewers the application's component scan finds: those of the core and of the previews. */
private val VIEWER_PACKAGES = arrayOf("dev.sluide.fileviewers")

/**
 * Every viewer bean, found by a component scan as in the application, so that the core's viewers need no import here.
 * In the order Spring lists them, which may differ between builds.
 */
internal val allViewers: List<FileViewer> by lazy {
    AnnotationConfigApplicationContext(*VIEWER_PACKAGES).use { context ->
        context.getBeansOfType(FileViewer::class.java).values.toList()
    }
}

/** [FileViewers] over [allViewers] in Spring's order and reversed: the viewers' ranks, not their order, must decide. */
internal fun allViewersInEitherOrder(): List<FileViewers> {
    val fallback = allViewers.filterIsInstance<DefaultTextFileViewer>().single()
    return listOf(allViewers, allViewers.reversed()).map { FileViewers(it, fallback) }
}

/** The simple class name of the viewer [viewers] picks for [file], also for core viewers this package cannot name. */
internal fun viewerNameFor(
    viewers: FileViewers,
    file: Path,
): String? = viewers.viewerFor(file)?.let { it::class.simpleName }
