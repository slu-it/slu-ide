package dev.sluide.codeintelligence.navigation

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.codeintelligence.binding.SourcePosition
import javafx.application.Platform
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

private val log = System.getLogger(DiskLines::class.java.name)

/** Files larger than this are not read for the line texts of a list (10 MiB). */
internal const val MAX_LINE_FILE_BYTES = 10L * 1024 * 1024

/** Reads the text of the lines of places in files, for the lists of choosers and usages. */
internal fun interface LineReader {
    /**
     * Starts reading the lines of [positions] and calls [done] once on the FX thread with the trimmed texts found (a
     * missing, unreadable or too large file gives none); closing the handle drops the answer.
     */
    fun read(
        positions: Collection<SourcePosition>,
        done: (Map<SourcePosition, String>) -> Unit,
    ): AutoCloseable
}

/** A [LineReader] reading from disk on a logged background thread, never on the FX thread. */
internal class DiskLines(
    private val executor: Executor = READER,
    private val dispatch: (Runnable) -> Unit = Platform::runLater,
    private val maxBytes: Long = MAX_LINE_FILE_BYTES,
) : LineReader {
    override fun read(
        positions: Collection<SourcePosition>,
        done: (Map<SourcePosition, String>) -> Unit,
    ): AutoCloseable {
        val dropped = AtomicBoolean(false)
        val wanted = positions.toList()
        executor.execute {
            val texts = readLineTexts(wanted, maxBytes)
            if (!dropped.get()) dispatch { if (!dropped.get()) done(texts) }
        }
        return AutoCloseable { dropped.set(true) }
    }

    private companion object {
        val READER: Executor by lazy { NamedThreads.single("code-intelligence-lines") }
    }
}

/** The trimmed texts of the lines of [positions], read file by file; files over [maxBytes] or unreadable are skipped. */
internal fun readLineTexts(
    positions: List<SourcePosition>,
    maxBytes: Long,
): Map<SourcePosition, String> {
    val texts = HashMap<SourcePosition, String>()
    for ((file, inFile) in positions.groupBy { it.file }) {
        val lines = readLines(file, inFile.map { it.line }.toSet(), maxBytes) ?: continue
        for (position in inFile) lines[position.line]?.let { texts[position] = it.trim() }
    }
    return texts
}

/** The texts of the [wanted] lines (0-based) of [file], `null` if it is too large or cannot be read. */
private fun readLines(
    file: Path,
    wanted: Set<Int>,
    maxBytes: Long,
): Map<Int, String>? {
    try {
        if (!Files.isRegularFile(file) || Files.size(file) > maxBytes) return null
        val last = wanted.maxOrNull() ?: return emptyMap()
        val found = HashMap<Int, String>()
        // Decoding replaces malformed bytes instead of failing, as a list line is only a hint.
        InputStreamReader(Files.newInputStream(file), Charsets.UTF_8).buffered().use { reader ->
            var index = 0
            while (index <= last) {
                val line = reader.readLine() ?: break
                if (index in wanted) found[index] = line
                index++
            }
        }
        return found
    } catch (e: IOException) {
        log.log(System.Logger.Level.DEBUG, "Could not read lines of $file", e)
        return null
    }
}
