package dev.sluide.previews.preview

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.TextDiff
import dev.sluide.previews.html.HtmlFileViewer
import dev.sluide.previews.image.ImageFileViewer
import dev.sluide.previews.image.SvgFileViewer
import dev.sluide.previews.json.JsonFileViewer
import dev.sluide.previews.notebook.NotebookFileViewer
import dev.sluide.previews.xml.XmlFileViewer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Which files the preview and image viewers open, and how [FileViewers] picks them by file and by name. */
class ViewerSelectionTest {
    @TempDir
    lateinit var dir: Path

    private val fallback = DefaultTextFileViewer()

    /** Names that the viewers under test must leave to others. */
    private val others =
        listOf("notes.txt", "README", "py", "README.md", "index.html", "icon.svg", "yarn.lock", "main.bicep")

    private fun file(name: String) = dir.resolve(name).apply { writeText("x = 1\n") }

    @Test
    fun `the JSON viewer opens its text files by extension and Pipfile lock by name`() {
        val json = JsonFileViewer()
        val all = listOf("package.json", "tsconfig.jsonc", "Pipfile.lock", "pipfile.LOCK") + others

        assertEquals(
            listOf("package.json", "tsconfig.jsonc", "Pipfile.lock", "pipfile.LOCK"),
            all.filter { json.canOpen(file(it)) },
        )
        assertFalse(json.canOpen(dir.resolve("missing.json")))
        assertFalse(json.canOpen(dir.resolve("binary.json").apply { writeBytes(byteArrayOf(0x61, 0x00, 0x62)) }))
        assertFalse(json.canOpen(dir.resolve("Pipfile.lock-binary").apply { writeBytes(byteArrayOf(0x61, 0)) }))
    }

    @Test
    fun `the XML viewer opens its text files by extension, and svg html and notebooks stay with theirs`() {
        val xml = XmlFileViewer()
        val own = listOf("pom.xml", "schema.xsd", "style.xsl", "transform.xslt", "view.fxml", "app.iml", "BUILD.XML")

        assertEquals(own, (own + others).filter { xml.canOpen(file(it)) })
        assertFalse(xml.canOpen(dir.resolve("missing.xml")))
        assertFalse(xml.canOpen(dir.resolve("binary.xml").apply { writeBytes(byteArrayOf(0x61, 0x00, 0x62)) }))

        val svg = SvgFileViewer()
        val html = HtmlFileViewer()
        val notebook = NotebookFileViewer()
        val viewers = FileViewers(listOf(fallback, xml, svg, html, notebook), fallback)
        assertSame(xml, viewers.viewerFor(file("pom.xml")))
        assertSame(svg, viewers.viewerFor(file("icon.svg")))
        assertSame(html, viewers.viewerFor(file("index.html")))
        assertSame(notebook, viewers.viewerFor(file("analysis.ipynb")))
    }

    @Test
    fun `Pipfile lock goes to the JSON viewer, which shows diffs, by file and by name`() {
        val json = JsonFileViewer()
        val viewers = FileViewers(listOf(json), fallback)

        assertSame(json, viewers.viewerFor(file("Pipfile.lock")))
        // What FileViewers picks the viewer by when it has only the name.
        assertEquals(setOf("Pipfile.lock"), json.fileNames)
        assertEquals(setOf("json", "jsonc"), json.fileExtensions)
        assertTrue(viewers.showsDiffs(json))
    }

    @Test
    fun `among all viewers, lock files open in the viewer of their format`() {
        for (viewers in allViewersInEitherOrder()) {
            assertTrue(viewers.viewerFor(file("Pipfile.lock")) is JsonFileViewer, "Pipfile.lock is JSON")
            assertEquals("TomlFileViewer", viewerNameFor(viewers, file("uv.lock")))
            assertTrue(viewers.viewerFor(file("yarn.lock")) is DefaultTextFileViewer)
        }
    }

    @Test
    fun `names like Dockerfile dot something open by their extension if a viewer takes it`() {
        val expected =
            mapOf(
                "Dockerfile.md" to "MarkdownFileViewer",
                "Caddyfile.json" to "JsonFileViewer",
                "Dockerfile.dev" to "DockerfileFileViewer",
                "Dockerfile.prod" to "DockerfileFileViewer",
                "Caddyfile.dev" to "CaddyfileFileViewer",
                "Dockerfile.dockerignore" to "IgnoreFileViewer",
                "Dockerfile.dev.dockerignore" to "IgnoreFileViewer",
            )

        for (viewers in allViewersInEitherOrder()) {
            for ((name, viewer) in expected) {
                assertEquals(viewer, viewerNameFor(viewers, dir.resolve(name).apply { writeText("x\n") }), name)
            }
        }
    }

    @Test
    fun `the image viewer opens images but SVG by extension, and has no diff of given texts`() {
        val image = ImageFileViewer()
        val viewers = FileViewers(listOf(image), fallback)

        assertEquals(setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico"), image.fileExtensions)
        assertNull(viewers.openDiff(dir.resolve("logo.png"), "", TextDiff("", emptyList())))
    }
}
