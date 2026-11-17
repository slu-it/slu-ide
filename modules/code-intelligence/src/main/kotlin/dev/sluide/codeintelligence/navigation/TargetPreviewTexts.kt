package dev.sluide.codeintelligence.navigation

import dev.sluide.fileviewers.PreviewTexts
import java.nio.file.Path
import java.util.concurrent.Executor

/** The unsaved text of a bound editor, read on the FX thread only when asked for. */
internal class BoundText(
    val file: Path,
    val text: () -> String,
)

/**
 * The texts for the preview under a list of places: the unsaved text of the [bound] editor of the file if there is
 * one (read on the FX thread), else the file from disk read on [executor] (UTF-8, malformed bytes replaced), `null`
 * for a file that is missing, not a regular file, unreadable or over [maxBytes]. Every answer goes through
 * [dispatch], which must run on the FX thread. FX thread.
 */
internal class TargetPreviewTexts(
    private val bound: () -> List<BoundText>,
    private val executor: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val maxBytes: Long = MAX_LINE_FILE_BYTES,
) : PreviewTexts {
    override fun load(
        file: Path,
        done: (String?) -> Unit,
    ) {
        val editor = bound().firstOrNull { sameFile(it.file, file) }
        if (editor != null) {
            val unsaved = editor.text()
            dispatch { done(unsaved) }
        } else {
            executor.execute {
                val text = readFileText(file, maxBytes)
                dispatch { done(text) }
            }
        }
    }
}
