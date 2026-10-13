package dev.sluide.fileexplorer

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
                "Main.java" to FileIcon.JAVA,
                "app.py" to FileIcon.PYTHON,
                "app.mjs" to FileIcon.JAVASCRIPT,
                "app.ts" to FileIcon.TYPESCRIPT,
                "index.htm" to FileIcon.HTML,
                "data.json" to FileIcon.JSON,
                "pom.xml" to FileIcon.XML,
                "ci.yml" to FileIcon.YAML,
                "README.md" to FileIcon.MARKDOWN,
                "main.rs" to FileIcon.RUST,
                "App.tsx" to FileIcon.REACT,
                "build.sh" to FileIcon.SHELL,
                "Main.scala" to FileIcon.CODE,
                "photo.jpeg" to FileIcon.JPG,
                "icon.webp" to FileIcon.IMAGE,
                "report.pdf" to FileIcon.PDF,
                "budget.xlsx" to FileIcon.EXCEL,
                "data.csv" to FileIcon.DELIMITED,
                "dist.tar.gz" to FileIcon.ARCHIVE,
                "lib.jar" to FileIcon.PACKAGE,
                "server.pem" to FileIcon.CERTIFICATE,
                "Cargo.lock" to FileIcon.LOCK,
                "app.properties" to FileIcon.CONFIG,
                "setup.exe" to FileIcon.WINDOWS,
                "notes.txt" to FileIcon.TEXT,
            )
        expected.forEach { (name, icon) -> assertEquals(icon, fileIcon(name), name) }
    }

    @Test
    fun `known file names win over extensions`() {
        val expected =
            mapOf(
                "Dockerfile" to FileIcon.DOCKER,
                "docker-compose.yml" to FileIcon.DOCKER,
                "package.json" to FileIcon.NODEJS,
                "package-lock.json" to FileIcon.LOCK,
                "build.gradle.kts" to FileIcon.GRADLE,
                "gradlew" to FileIcon.GRADLE,
                "CMakeLists.txt" to FileIcon.MAKE,
                "Makefile" to FileIcon.MAKE,
                ".gitignore" to FileIcon.GIT,
                ".editorconfig" to FileIcon.CONFIG,
                "LICENSE" to FileIcon.LICENSE,
                "Gemfile" to FileIcon.RUBY,
                ".gitlab-ci.yml" to FileIcon.GITLAB,
            )
        expected.forEach { (name, icon) -> assertEquals(icon, fileIcon(name), name) }
    }

    @Test
    fun `names and extensions are case-insensitive`() {
        assertEquals(FileIcon.JAVA, fileIcon("Main.JAVA"))
        assertEquals(FileIcon.YAML, fileIcon("CI.Yaml"))
        assertEquals(FileIcon.LICENSE, fileIcon("License.md"))
    }

    @Test
    fun `other files are text or binary depending on readability`() {
        assertEquals(FileIcon.TEXT, fileIcon("notes.adoc") { true })
        assertEquals(FileIcon.TEXT, fileIcon("Procfile") { true })
        assertEquals(FileIcon.BINARY, fileIcon("data.bin") { false })
        assertEquals(FileIcon.BINARY, fileIcon(".DS_Store") { false })
    }

    @Test
    fun `every name and extension belongs to one icon and is lower case`() {
        for (key in listOf(FileIcon::names, FileIcon::extensions)) {
            val owners =
                FileIcon.entries
                    .flatMap { icon ->
                        key(icon).map { it to icon }
                    }.groupBy({ it.first }) { it.second }
            val ambiguous = owners.filterValues { it.size > 1 }
            assertTrue(ambiguous.isEmpty(), "Mapped more than once: $ambiguous")
            owners.keys.forEach { assertEquals(it.lowercase(), it) }
        }
    }

    @Test
    fun `every style class has a colour in the stylesheet`() {
        val css = checkNotNull(FileExplorerView::class.java.getResource("file-explorer.css")).readText()
        val missing =
            FileIcon.entries
                .map { it.styleClass }
                .distinct()
                .filterNot { Regex("""\.$it\s*\{""").containsMatchIn(css) }
        assertTrue(missing.isEmpty(), "No CSS rule for $missing")
    }
}
