package dev.sluide.ebooks.view

import dev.sluide.projectdata.ProjectDataStore
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/** Where the reading position of one book is kept. [write] may be called off the FX thread. */
internal interface PositionStore {
    fun read(): Int?

    fun write(offset: Int)
}

/** The position as per-file project data: field `ebook` with `{"offset": N}` (ADR 0101, ADR 0165). */
internal class ProjectDataPositionStore(
    private val data: ProjectDataStore,
    private val location: BookLocation,
) : PositionStore {
    override fun read(): Int? {
        val node = data.fileData(location.file, location.projectFolder)?.get(FIELD)?.get(OFFSET)
        return node?.takeIf { it.isIntegralNumber }?.asInt()
    }

    override fun write(offset: Int) {
        data.updateFileData(location.file, location.projectFolder) { root ->
            (root.get(FIELD) as? ObjectNode ?: root.putObject(FIELD)).put(OFFSET, offset)
        }
    }

    private companion object {
        const val FIELD = "ebook"
        const val OFFSET = "offset"
    }
}

/**
 * The remembered reading position of a book (ADR 0165): [changed] saves the latest offset through the [writer], at
 * most once per [delay] (changes within it end in one write); [flush] and [close] write a pending one at once, on the
 * caller's thread; after [close] nothing is written. Thread-safe.
 */
internal class BookPosition(
    private val store: PositionStore,
    private val writer: Executor,
    private val delay: Duration = Duration.ofSeconds(1),
) {
    private val pending = AtomicReference<Int?>()
    private val writeLock = Any()

    @Volatile
    private var closed = false

    /** The saved offset kept within `[0, textLength]`, or `null` if none was saved. */
    fun read(textLength: Int): Int? = store.read()?.coerceIn(0, textLength)

    fun changed(offset: Int) {
        if (closed) return
        if (pending.getAndSet(offset) == null) {
            writer.execute {
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                flush()
            }
        }
    }

    fun flush() {
        synchronized(writeLock) { pending.getAndSet(null)?.let(store::write) }
    }

    /** Writes a pending offset now and ignores later changes. */
    fun close() {
        closed = true
        flush()
    }
}
