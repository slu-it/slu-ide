package dev.sluide.fileexplorer

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class FileIconTest {
    private val notCalled: () -> Boolean = { fail("readable must not be checked") }

    private fun fileIcon(
        name: String,
        readable: () -> Boolean = notCalled,
    ) = fileIconFor(Path.of("project", name), directory = false, expanded = false, readable = readable)

    @Test
    fun `folders are closed or open folders without checking readability`() {
        assertEquals(FileIcon.FOLDER, fileIconFor(Path.of("src.kt"), directory = true, expanded = false, notCalled))
        assertEquals(FileIcon.FOLDER_OPEN, fileIconFor(Path.of("src.kt"), directory = true, expanded = true, notCalled))
    }

    @Test
    fun `known extensions get their icon without checking readability`() {
        val expected =
            mapOf(
                "Main.kt" to FileIcon.KOTLIN,
                "build.gradle.kts" to FileIcon.KOTLIN,
                "Main.java" to FileIcon.JAVA,
                "app.py" to FileIcon.PYTHON,
                "stubs.pyi" to FileIcon.PYTHON,
                "app.mjs" to FileIcon.JAVASCRIPT,
                "app.ts" to FileIcon.TYPESCRIPT,
                "index.htm" to FileIcon.HTML,
                "package.json" to FileIcon.JSON,
                "pom.xml" to FileIcon.XML,
                "logo.svg" to FileIcon.XML,
                "ci.yml" to FileIcon.YAML,
                "README.md" to FileIcon.MARKDOWN,
            )
        expected.forEach { (name, icon) -> assertEquals(icon, fileIcon(name), name) }
    }

    @Test
    fun `extensions are case-insensitive`() {
        assertEquals(FileIcon.JAVA, fileIcon("Main.JAVA"))
        assertEquals(FileIcon.YAML, fileIcon("CI.Yaml"))
    }

    @Test
    fun `other files are text or binary depending on readability`() {
        assertEquals(FileIcon.TEXT, fileIcon("notes.txt") { true })
        assertEquals(FileIcon.TEXT, fileIcon("Dockerfile") { true })
        assertEquals(FileIcon.BINARY, fileIcon("image.png") { false })
        assertEquals(FileIcon.BINARY, fileIcon(".DS_Store") { false })
    }
}
