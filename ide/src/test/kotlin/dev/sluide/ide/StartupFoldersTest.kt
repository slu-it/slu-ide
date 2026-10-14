package dev.sluide.ide

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals

class StartupFoldersTest {
    @TempDir
    lateinit var dir: Path

    private val warnings = mutableListOf<String>()

    @Test
    fun `folders are resolved to absolute, normalized paths in order`() {
        val a = dir.resolve("a").createDirectory()
        val b = dir.resolve("b").createDirectory()

        val folders = startupFolders(listOf("$b", "$a/../a/."), warnings::add)

        assertEquals(listOf(b.toAbsolutePath().normalize(), a.toAbsolutePath().normalize()), folders)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `relative folders are resolved against the working directory`() {
        assertEquals(listOf(Path.of("").toAbsolutePath()), startupFolders(listOf("."), warnings::add))
    }

    @Test
    fun `files, missing paths and invalid paths are reported and skipped`() {
        val file = dir.resolve("notes.txt").createFile()
        val missing = dir.resolve("missing")

        val folders = startupFolders(listOf("$file", "$missing", "bad\u0000path", "$dir"), warnings::add)

        assertEquals(listOf(dir.toAbsolutePath().normalize()), folders)
        assertEquals(3, warnings.size)
        assertEquals("SluIde: not a folder, ignored: $file", warnings.first())
    }

    @Test
    fun `no arguments open no folder`() {
        assertEquals(emptyList(), startupFolders(emptyList(), warnings::add))
    }
}
