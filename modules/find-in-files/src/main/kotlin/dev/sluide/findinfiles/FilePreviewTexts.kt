package dev.sluide.findinfiles

import dev.sluide.findinfiles.search.FileText
import dev.sluide.findinfiles.search.SearchLimits
import dev.sluide.findinfiles.search.readText
import dev.sluide.findinfiles.ui.PreviewTexts
import java.nio.file.Path
import java.util.concurrent.Executor

private val log = System.getLogger(FilePreviewTexts::class.java.name)

/**
 * The preview's texts: a file's unsaved buffer (as of the call, taken on the FX thread) first, else the file read on
 * [background]; binary, unreadable and too large files give `null`. [done] runs through [runOnFx].
 */
internal class FilePreviewTexts(
    private val unsavedTexts: () -> Map<Path, String>,
    private val background: Executor,
    private val runOnFx: (Runnable) -> Unit,
) : PreviewTexts {
    override fun load(
        file: Path,
        done: (String?) -> Unit,
    ) {
        val key = file.toAbsolutePath().normalize()
        val unsaved = unsavedTexts().mapKeys { it.key.toAbsolutePath().normalize() }
        val maxBytes = SearchLimits().maxBytes
        if (key in unsaved) {
            val text = (readText(key, unsaved, maxBytes) as? FileText.Text)?.text
            runOnFx { done(text) }
        } else {
            background.execute {
                val text =
                    try {
                        (readText(key, emptyMap(), maxBytes) as? FileText.Text)?.text
                    } catch (e: Exception) {
                        log.log(System.Logger.Level.WARNING, "Find in Files preview failed", e)
                        null
                    }
                runOnFx { done(text) }
            }
        }
    }
}
