package dev.sluide.codeintelligence.navigation

import dev.sluide.fileviewers.PreviewTexts
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** The unsaved text of a bound editor, read on the FX thread only when asked for. */
internal class BoundText(
    val file: Path,
    val text: () -> String,
)

/**
 * The texts for the preview under a list of places: the unsaved text of the [bound] editor of the file if there is
 * one (read on the FX thread), else the file from disk read on [executor] (UTF-8, malformed bytes replaced), `null`
 * for a file that is missing, not a regular file, unreadable or over [maxBytes]. Every answer goes through
 * [dispatch], which must run on the FX thread. A queued read that a newer [load] overtook answers `null` without
 * reading: a `FilePreview` uses only the answer to its latest load, so holding a key across many files does not queue
 * whole-file reads on the shared thread that later line texts wait behind. One per window, used by one popup at a
 * time. FX thread.
 */
internal class TargetPreviewTexts(
    private val bound: () -> List<BoundText>,
    private val executor: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val maxBytes: Long = MAX_LINE_FILE_BYTES,
) : PreviewTexts {
    /** Counts the loads asked; a queued read runs only while it is still the latest. */
    private val latest = AtomicLong()

    override fun load(
        file: Path,
        done: (String?) -> Unit,
    ) {
        val mine = latest.incrementAndGet()
        val editor = bound().firstOrNull { sameFile(it.file, file) }
        if (editor != null) {
            val unsaved = editor.text()
            dispatch { done(unsaved) }
        } else {
            executor.execute {
                val text = if (latest.get() == mine) readFileText(file, maxBytes) else null
                dispatch { done(text) }
            }
        }
    }
}
