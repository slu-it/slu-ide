package dev.sluide.fileviewers

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/** Which files each code viewer accepts. Add a row per viewer. */
class CodeFileViewersTest {
    @TempDir
    lateinit var dir: Path

    private val samples: Map<CodeFileViewer, List<String>> =
        mapOf(
            PythonFileViewer() to listOf("main.py", "gui.pyw", "types.pyi", "LEGACY.PY"),
            KotlinFileViewer() to listOf("Main.kt", "build.gradle.kts"),
            JavaFileViewer() to listOf("Main.java"),
            JavaScriptFileViewer() to listOf("app.js", "module.mjs", "config.cjs"),
            TypeScriptFileViewer() to listOf("app.ts", "types.d.ts", "module.mts", "config.cts"),
            JsonFileViewer() to listOf("package.json", "tsconfig.jsonc"),
            XmlFileViewer() to listOf("pom.xml", "schema.xsd", "style.xsl", "transform.xslt", "icon.svg", "view.fxml"),
            HtmlFileViewer() to listOf("index.html", "legacy.htm", "page.xhtml"),
        )

    private val others = listOf("notes.txt", "README", "py")

    private fun file(name: String) = dir.resolve(name).apply { writeText("x = 1\n") }

    @Test
    fun `every code viewer opens exactly its own text files`() {
        val all = samples.values.flatten() + others
        for ((viewer, own) in samples) {
            val accepted = all.filter { viewer.canOpen(file(it)) }
            assertEquals(own, accepted, viewer::class.simpleName)
        }
    }

    @Test
    fun `code viewers do not open binary or missing files`() {
        for ((viewer, own) in samples) {
            val name = own.first()
            assertFalse(viewer.canOpen(dir.resolve("missing-$name")), "missing $name")
            val binary = dir.resolve("binary-$name").apply { writeBytes(byteArrayOf(0x61, 0x00, 0x62)) }
            assertFalse(viewer.canOpen(binary), "binary $name")
        }
    }

    @Test
    fun `code viewers are chosen before the default text viewer`() {
        val fallback = DefaultTextFileViewer()
        val viewers = FileViewers(listOf(fallback) + samples.keys, fallback)

        for ((viewer, own) in samples) {
            assertSame(viewer, viewers.viewerFor(file(own.first())))
        }
        assertSame(fallback, viewers.viewerFor(file("notes.txt")))
    }
}
