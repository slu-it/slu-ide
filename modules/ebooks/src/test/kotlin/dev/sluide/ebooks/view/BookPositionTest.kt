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
        var saved: Int? = null,
    ) : PositionStore {
        val writes = java.util.concurrent.CopyOnWriteArrayList<Int>()

        override fun read() = saved

        override fun write(offset: Int) {
            writes += offset
            saved = offset
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
        assertEquals(listOf(30), store.writes)
    }

    @Test
    fun `a change after a write is written again`() {
        val store = MemoryStore()
        val position = BookPosition(store, executor, Duration.ofMillis(10))
        position.changed(1)
        awaitIdle()
        position.changed(2)
        awaitIdle()
        assertEquals(listOf(1, 2), store.writes)
    }

    @Test
    fun `close writes a pending offset at once and ignores later changes`() {
        val store = MemoryStore()
        val position = BookPosition(store, executor, Duration.ofSeconds(30))
        position.changed(42)
        assertTrue(store.writes.isEmpty())
        position.close()
        assertEquals(listOf(42), store.writes)
        position.changed(50)
        position.close()
        assertEquals(listOf(42), store.writes)
    }

    @Test
    fun `reads the saved offset within the text`() {
        assertEquals(500, BookPosition(MemoryStore(500), executor).read(1000))
        assertEquals(1000, BookPosition(MemoryStore(5000), executor).read(1000))
        assertEquals(0, BookPosition(MemoryStore(-3), executor).read(1000))
        assertNull(BookPosition(MemoryStore(null), executor).read(1000))
    }

    @Test
    fun `project data holds the offset under the ebook field and nothing else`() {
        val data = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val book = dir.resolve("project/book.epub")
        val location = BookLocation(book).apply { projectFolder = dir.resolve("project") }
        val store = ProjectDataPositionStore(data, location)
        assertNull(store.read())
        store.write(1234)
        assertEquals(1234, store.read())
        assertEquals("""{"ebook":{"offset":1234}}""", data.fileData(book, dir.resolve("project")).toString())
        store.write(7)
        assertEquals(7, ProjectDataPositionStore(data, location).read())
    }

    @Test
    fun `a book that was moved saves under its new name`() {
        val data = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val project = dir.resolve("project")
        val location = BookLocation(project.resolve("old.epub")).apply { projectFolder = project }
        val store = ProjectDataPositionStore(data, location)
        location.file = project.resolve("new.epub")
        store.write(99)
        assertEquals(99, store.read())
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
