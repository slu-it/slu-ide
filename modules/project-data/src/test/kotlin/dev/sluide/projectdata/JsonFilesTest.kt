package dev.sluide.projectdata

import dev.sluide.testsupport.LogCapture
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonFilesTest {
    @TempDir
    lateinit var dir: Path

    private fun node(json: String) = mapper.readTree(json) as ObjectNode

    /** A value Jackson cannot serialize: its getter throws. */
    private class Unserializable {
        @Suppress("unused")
        val value: String get() = error("boom")
    }

    @Test
    fun `writing creates the folders and the file, and no temporary file stays`() {
        val file = dir.resolve("a/b/data.json")

        assertTrue(writeJson(file, node("""{"x": 1}""")))

        assertEquals(node("""{"x": 1}"""), (readJson(file) as JsonRead.Found).node)
        assertEquals(listOf(file), file.parent.listDirectoryEntries())
    }

    @Test
    fun `a target that cannot be replaced fails, leaves no temporary file and keeps what is there`() {
        val target = dir.resolve("data.json").createDirectories()
        val inside = target.resolve("keep.txt").apply { writeText("keep") }

        LogCapture(ProjectDataStore::class.java.name).use { log ->
            assertFalse(writeJson(target, node("""{"x": 1}""")))

            assertEquals(listOf("Cannot save the project data to $target"), log.messages())
        }

        assertEquals("keep", inside.readText())
        assertEquals(listOf(target), dir.listDirectoryEntries())
    }

    @Test
    fun `a tree that cannot be serialized fails, keeps the old file and leaves no temporary file`() {
        val file = dir.resolve("data.json").apply { writeText("""{"old": true}""") }
        val tree = node("{}").apply { putPOJO("bad", Unserializable()) }

        LogCapture(ProjectDataStore::class.java.name).use { log ->
            assertFalse(writeJson(file, tree))

            assertEquals(listOf("Cannot save the project data to $file"), log.messages())
        }

        assertEquals("""{"old": true}""", file.readText())
        assertEquals(listOf(file), dir.listDirectoryEntries())
    }

    @ParameterizedTest(name = "{0} is writable: {1}")
    @CsvSource(
        "'{}', true",
        "'{\"version\": 0}', true",
        "'{\"version\": 1}', true",
        "'{\"version\": 2}', false",
        "'{\"version\": 1.5}', false",
        "'{\"version\": \"1\"}', false",
        "'{\"version\": null}', false",
        "'{\"version\": true}', false",
    )
    fun `a tree is writable when its version is missing or a number up to ours`(
        json: String,
        writable: Boolean,
    ) {
        assertEquals(writable, node(json).writable())
    }
}
