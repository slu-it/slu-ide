package dev.sluide.ebooks.view

import dev.sluide.projectdata.ProjectDataStore
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * A reading position: the whole-book [offset] and, in chapter mode, the [chapter] (list position) shown; an offset
 * alone cannot name a chapter without text, which shares its offset with the next one.
 */
internal data class SavedPosition(
    val offset: Int,
    val chapter: Int? = null,
)

/** Where the reading position of one book is kept. [write] may be called off the FX thread. */
internal interface PositionStore {
    fun read(): SavedPosition?

    fun write(position: SavedPosition)
}

/** The position as per-file project data: field `ebook` with `{"offset": N, "chapter": C}` (ADR 0101, ADR 0165). */
internal class ProjectDataPositionStore(
    private val data: ProjectDataStore,
    private val location: BookLocation,
) : PositionStore {
    override fun read(): SavedPosition? {
        val field = data.fileData(location.file, location.projectFolder)?.get(FIELD)
        val offset = field?.get(OFFSET)?.takeIf { it.isIntegralNumber }?.asInt() ?: return null
        // Data of an older version has no chapter.
        val chapter = field.get(CHAPTER)?.takeIf { it.isIntegralNumber }?.asInt()
        return SavedPosition(offset, chapter)
    }

    override fun write(position: SavedPosition) {
        data.updateFileData(location.file, location.projectFolder) { root ->
            val field = root.get(FIELD) as? ObjectNode ?: root.putObject(FIELD)
            field.put(OFFSET, position.offset)
            if (position.chapter != null) field.put(CHAPTER, position.chapter) else field.remove(CHAPTER)
        }
    }

    private companion object {
        const val FIELD = "ebook"
        const val OFFSET = "offset"
        const val CHAPTER = "chapter"
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
    private val pending = AtomicReference<SavedPosition?>()
    private val writeLock = Any()

    @Volatile
    private var closed = false

    /** The saved position with its offset kept within `[0, textLength]`, or `null` if none was saved. */
    fun read(textLength: Int): SavedPosition? =
        store.read()?.let { it.copy(offset = it.offset.coerceIn(0, textLength)) }

    fun changed(
        offset: Int,
        chapter: Int? = null,
    ) {
        if (closed) return
        if (pending.getAndSet(SavedPosition(offset, chapter)) == null) {
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

    /** Writes a pending position now and ignores later changes. */
    fun close() {
        closed = true
        flush()
    }
}
