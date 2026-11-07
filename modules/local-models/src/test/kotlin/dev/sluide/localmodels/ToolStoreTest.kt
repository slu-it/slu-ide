package dev.sluide.localmodels

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The layout rules of [ToolStore] that [ToolInstallerTest] cannot reach through an install. */
class ToolStoreTest {
    @TempDir
    lateinit var dir: Path

    private val root by lazy { dir.resolve("ty") }
    private var failing: String? = null
    private val store by lazy {
        ToolStore(root) { path, text ->
            if (path.name == failing) throw IOException("disk full")
            writeAtomically(path, text)
        }
    }

    /** A marked version folder [folder] with an executable `ty`. */
    private fun installed(
        folder: String,
        version: String = folder,
    ): Path {
        val path = root.resolve(folder).createDirectories()
        path.resolve("ty").writeText("#!/bin/sh\n")
        makeExecutable(path.resolve("ty"))
        store.mark(path, version, "ty")
        return path
    }

    private fun pointer(name: String) = root.resolve(name).readText()

    private fun entries() = root.listDirectoryEntries().map { it.name }.sorted()

    @Test
    fun `a failed write of current puts previous back`() {
        store.switchTo(installed("1"))
        store.switchTo(installed("2"))
        val third = installed("3")
        failing = "current"

        assertFailsWith<IOException> { store.switchTo(third) }

        assertEquals("2", pointer("current"))
        assertEquals("1", pointer("previous"))
    }

    @Test
    fun `prune keeps the folders the pointers name, also one that does not read as installed right now`() {
        store.switchTo(installed("1"))
        store.switchTo(installed("2"))
        store.switchTo(installed("3"))
        Files.delete(root.resolve("2/ty"))
        assertNull(store.read().previous)

        store.prune()

        assertEquals(listOf("2", "3", "current", "previous"), entries())
    }

    @Test
    fun `prune and delete remove only what installs create`() {
        store.switchTo(installed("1"))
        installed("2")
        root.resolve(".install-123456/.content").createDirectories()
        root.resolve(".current-${UUID.randomUUID()}.tmp").writeText("2")
        root.resolve("notes.txt").writeText("mine")
        root.resolve("Documents").createDirectories()
        root.resolve(".hidden").writeText("mine")

        store.prune()

        assertEquals(listOf(".hidden", "1", "Documents", "current", "notes.txt"), entries())

        store.deleteAll()

        assertEquals(listOf(".hidden", "Documents", "notes.txt"), entries())
        listOf(".hidden", "Documents", "notes.txt").forEach { Files.delete(root.resolve(it)) }
        store.switchTo(installed("1"))

        store.deleteAll()

        assertFalse(root.exists(), "an empty root goes too")
    }

    @Test
    fun `a previous pointer on the current folder counts as none`() {
        installed("1")
        root.resolve("current").writeText("1")
        root.resolve("previous").writeText("1")

        assertEquals("1", store.read().current?.version)
        assertNull(store.read().previous)
        assertFalse(store.rollback())
    }
}
