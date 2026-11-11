package dev.sluide.ebooks.view

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookPositionTest {
    @TempDir
    lateinit var dir: Path

    private class MemoryStore(
        var saved: SavedPosition? = null,
    ) : PositionStore {
        constructor(offset: Int) : this(SavedPosition(offset))

        val writes = java.util.concurrent.CopyOnWriteArrayList<SavedPosition>()

        /** The offsets written, in order. */
        val offsets get() = writes.map { it.offset }

        override fun read() = saved

        override fun write(position: SavedPosition) {
            writes += position
            saved = position
        }
    }

    private val executor = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }

    private fun awaitIdle() = executor.submit {}.get(10, TimeUnit.SECONDS)

    @Test
    fun `changes within the delay end in one write of the latest`() {
        val store = MemoryStore()
        val position = BookPosition(store, executor, Duration.ofMillis(100))
        position.changed(10)
        position.changed(20)
        position.changed(30)
        awaitIdle()
        assertEquals(listOf(30), store.offsets)
    }

    @Test
    fun `a change after a write is written again`() {
        val store = MemoryStore()
        val position = BookPosition(store, executor, Duration.ofMillis(10))
        position.changed(1)
        awaitIdle()
        position.changed(2)
        awaitIdle()
        assertEquals(listOf(1, 2), store.offsets)
    }

    @Test
    fun `close writes a pending offset at once and ignores later changes`() {
        val store = MemoryStore()
        val position = BookPosition(store, executor, Duration.ofSeconds(30))
        position.changed(42)
        assertTrue(store.writes.isEmpty())
        position.close()
        assertEquals(listOf(42), store.offsets)
        position.changed(50)
        position.close()
        assertEquals(listOf(42), store.offsets)
    }

    @Test
    fun `reads the saved offset within the text`() {
        assertEquals(500, BookPosition(MemoryStore(500), executor).read(1000)?.offset)
        assertEquals(1000, BookPosition(MemoryStore(5000), executor).read(1000)?.offset)
        assertEquals(0, BookPosition(MemoryStore(-3), executor).read(1000)?.offset)
        assertNull(BookPosition(MemoryStore(null), executor).read(1000))
    }

    @Test
    fun `the chapter is written with the latest offset and read back`() {
        val store = MemoryStore()
        val position = BookPosition(store, executor, Duration.ofMillis(100))
        position.changed(10, 1)
        position.changed(20, 2)
        awaitIdle()
        assertEquals(listOf(SavedPosition(20, 2)), store.writes)
        assertEquals(SavedPosition(20, 2), BookPosition(store, executor).read(1000))
    }

    @Test
    fun `project data holds the offset under the ebook field and nothing else`() {
        val data = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val book = dir.resolve("project/book.epub")
        val location = BookLocation(book).apply { projectFolder = dir.resolve("project") }
        val store = ProjectDataPositionStore(data, location)
        assertNull(store.read())
        store.write(SavedPosition(1234))
        assertEquals(SavedPosition(1234), store.read())
        assertEquals("""{"ebook":{"offset":1234}}""", data.fileData(book, dir.resolve("project")).toString())
        store.write(SavedPosition(7, 3))
        assertEquals(SavedPosition(7, 3), ProjectDataPositionStore(data, location).read())
        assertEquals("""{"ebook":{"offset":7,"chapter":3}}""", data.fileData(book, dir.resolve("project")).toString())
        // Data without a chapter (older versions, whole-book mode) reads as no chapter and drops a stale one.
        store.write(SavedPosition(9))
        assertEquals(SavedPosition(9, null), store.read())
    }

    @Test
    fun `a book that was moved saves under its new name`() {
        val data = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val project = dir.resolve("project")
        val location = BookLocation(project.resolve("old.epub")).apply { projectFolder = project }
        val store = ProjectDataPositionStore(data, location)
        location.file = project.resolve("new.epub")
        store.write(SavedPosition(99))
        assertEquals(SavedPosition(99), store.read())
        assertNull(data.fileData(project.resolve("old.epub"), project))
        assertEquals(
            99,
            data
                .fileData(project.resolve("new.epub"), project)
                ?.get("ebook")
                ?.get("offset")
                ?.asInt(),
        )
    }
}
