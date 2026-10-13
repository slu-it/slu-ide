package dev.sluide.ide

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProjectServiceTest {
    @TempDir
    lateinit var dir: Path

    private val service = ProjectService()

    @Test
    fun `no project is open initially`() {
        assertNull(service.projectFolder.get())
    }

    @Test
    fun `opening a folder updates the property and notifies listeners`() {
        val seen = mutableListOf<Path?>()
        service.projectFolder.subscribe { folder: Path? -> seen += folder }

        service.open(dir)

        assertEquals(dir.toAbsolutePath().normalize(), service.projectFolder.get())
        assertEquals(listOf(null, dir.toAbsolutePath().normalize()), seen)
    }

    @Test
    fun `a project folder is set once, reopening it changes nothing`() {
        service.open(dir)
        service.open(dir.resolve(".").resolve("."))

        assertFailsWith<IllegalStateException> { service.open(dir.resolve("other").createDirectory()) }
        assertEquals(dir.toAbsolutePath().normalize(), service.projectFolder.get())
    }

    @Test
    fun `opening a file is rejected and keeps the current project`() {
        service.open(dir)

        assertFailsWith<IllegalArgumentException> { service.open(dir.resolve("f.txt").createFile()) }
        assertEquals(dir.toAbsolutePath().normalize(), service.projectFolder.get())
    }
}
