package dev.sluide.fileviewers.preview

import java.io.IOException
import java.nio.file.Path

/** A preview's page as a self-contained HTML file next to the previewed file. */
internal interface HtmlExport {
    /** The file the export of [file] is written to. */
    fun target(file: Path): Path

    /** The page for [text], the saved content of [file]; blocking (may download images), never on the FX thread. */
    @Throws(IOException::class)
    fun render(
        file: Path,
        text: String,
    ): ExportedPage
}

/**
 * An exported [html] page; [problems] are lines for the user about what it lacks (an image that could not be embedded,
 * a diagram that could not be drawn).
 */
internal class ExportedPage(
    val html: String,
    val problems: List<String>,
)
