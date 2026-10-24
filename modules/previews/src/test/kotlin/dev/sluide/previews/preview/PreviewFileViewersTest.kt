package dev.sluide.previews.preview

import dev.sluide.fileviewers.Highlighters
import dev.sluide.previews.bicep.BicepFileViewer
import dev.sluide.previews.html.HtmlFileViewer
import dev.sluide.previews.image.SvgFileViewer
import dev.sluide.previews.markdown.MermaidFileViewer
import dev.sluide.previews.pem.PemFileViewer
import dev.sluide.previews.pem.PemPreview
import dev.sluide.previews.table.CookiesFileViewer
import dev.sluide.previews.workflow.WorkflowFileViewer
import dev.sluide.previews.yaml.YamlFileViewer
import dev.sluide.previews.yaml.YamlPreview
import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which files the preview and image viewers accept. */
class PreviewFileViewersTest {
    @TempDir
    lateinit var dir: Path

    private fun text(name: String) = dir.resolve(name).apply { writeText("<x/>\n") }

    private fun binary(name: String) =
        dir.resolve(name).apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x00, 0x01)) }

    @Test
    fun `HTML and SVG previews open their text files`() {
        val names = listOf("index.html", "old.HTM", "page.xhtml", "icon.svg", "notes.txt", "pom.xml")
        assertEquals(listOf("index.html", "old.HTM", "page.xhtml"), names.filter { HtmlFileViewer().canOpen(text(it)) })
        assertEquals(listOf("icon.svg"), names.filter { SvgFileViewer().canOpen(text(it)) })
        assertFalse(SvgFileViewer().canOpen(binary("broken.svg")))
    }

    @Test
    fun `Mermaid previews open their text files`() {
        val names = listOf("flow.mmd", "Chart.MERMAID", "a.md", "flow.mmd.txt", "mermaid")
        assertEquals(listOf("flow.mmd", "Chart.MERMAID"), names.filter { MermaidFileViewer().canOpen(text(it)) })
        assertFalse(MermaidFileViewer().canOpen(binary("broken.mmd")))
    }

    @Test
    fun `Bicep files open with their resource graph, parameter files as code`() {
        val names = listOf("main.bicep", "Infra.BICEP", "main.bicepparam", "main.bicep.json", "bicep")
        assertEquals(listOf("main.bicep", "Infra.BICEP"), names.filter { BicepFileViewer().canOpen(text(it)) })
        assertFalse(BicepFileViewer().canOpen(binary("broken.bicep")))
        for (viewers in allViewersInEitherOrder()) {
            assertEquals("BicepParamFileViewer", viewerNameFor(viewers, text("main.bicepparam")))
        }
    }

    @Test
    fun `workflows open with their graph only directly in a workflows folder of a github folder`() {
        val viewer = WorkflowFileViewer()
        val workflows = dir.resolve(".github/workflows").createDirectories()
        val names = listOf("ci.yml", "Release.YAML", "notes.txt", "ci.yml.bak")
        assertEquals(
            listOf("ci.yml", "Release.YAML"),
            names.filter { viewer.canOpen(workflows.resolve(it).apply { writeText("on: push\n") }) },
        )
        assertFalse(viewer.canOpen(text("ci.yml")), "not in .github/workflows")
        assertFalse(viewer.canOpen(workflows.resolve("missing.yml")))
        assertFalse(viewer.canOpen(workflows.resolve("binary.yml").apply { writeBytes(byteArrayOf(0x61, 0)) }))
        assertTrue(viewer.matchesPath(Path.of(".github/workflows/ci.yml")))
        assertFalse(viewer.matchesPath(Path.of(".github/workflows/sub/ci.yml")))
        assertEquals(setOf("yml", "yaml"), viewer.fileExtensions)
    }

    @Test
    fun `YAML files open with a foldable preview in Split that copies its text formatted`() {
        val viewer = YamlFileViewer()
        val names = listOf("config.yml", "values.YAML", "notes.txt", "config.yml.bak", "yml")

        assertEquals(listOf("config.yml", "values.YAML"), names.filter { viewer.canOpen(text(it)) })
        assertFalse(viewer.canOpen(binary("broken.yaml")))
        assertEquals(PreviewMode.SPLIT, YamlPreview.defaultMode)
        assertTrue(YamlPreview.folds && YamlPreview.copiesFormatted && YamlPreview.syncsScroll)
        assertEquals(CommentSyntax.HASH, viewer.comment)
    }

    @Test
    fun `cookie jars open as tables`() {
        val names = listOf("http-client.cookies", "saved.COOKIES", "cookies", "cookies.txt")
        assertEquals(names.take(2), names.filter { CookiesFileViewer().canOpen(text(it)) })
        assertFalse(CookiesFileViewer().canOpen(binary("broken.cookies")))
    }

    @Test
    fun `PEM details open certificates and keys holding PEM text`() {
        val viewer = PemFileViewer()
        val pem = "Bag Attributes: x\n-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"
        val names = listOf("cert.pem", "site.CRT", "ca.cer", "tls.key", "cert.txt", "cert.der")
        val opened = names.filter { viewer.canOpen(dir.resolve(it).apply { writeText(pem) }) }

        assertEquals(listOf("cert.pem", "site.CRT", "ca.cer", "tls.key"), opened)
        assertFalse(viewer.canOpen(dir.resolve("raw.key").apply { writeText("not a PEM key\n") }), "plain text")
        assertFalse(viewer.canOpen(binary("binary.cer")), "DER")
        assertEquals(PreviewMode.PREVIEW, PemPreview.defaultMode)
        assertEquals(CommentSyntax.NONE, viewer.comment)
    }

    @Test
    fun `a preview that folds cannot offer scripts, zoom or have an outline`() {
        fun folding(
            scripts: Boolean = false,
            zoom: Boolean = false,
            outline: Boolean = false,
        ) = object : Preview {
            override fun load(
                engine: WebEngine,
                file: Path,
                text: String,
                dark: Boolean,
                root: Path,
            ) = Unit

            override val folds get() = true
            override val offersScripts get() = scripts
            override val zooms get() = zoom
            override val hasOutline get() = outline
        }
        for (preview in listOf(folding(scripts = true), folding(zoom = true), folding(outline = true))) {
            assertFailsWith<IllegalArgumentException> {
                PreviewFileView(text("page.html"), Highlighters.xml, preview)
            }
        }
    }

    @Test
    fun `a preview cannot both offer scripts and zoom`() {
        val both =
            object : Preview {
                override fun load(
                    engine: WebEngine,
                    file: Path,
                    text: String,
                    dark: Boolean,
                    root: Path,
                ) = Unit

                override val offersScripts get() = true
                override val zooms get() = true
            }
        assertFailsWith<IllegalArgumentException> { PreviewFileView(text("page.html"), Highlighters.xml, both) }
    }

    @Test
    fun `a preview that copies its text formatted must fold`() {
        val copying =
            object : Preview {
                override fun load(
                    engine: WebEngine,
                    file: Path,
                    text: String,
                    dark: Boolean,
                    root: Path,
                ) = Unit

                override val copiesFormatted get() = true
            }
        assertFailsWith<IllegalArgumentException> {
            PreviewFileView(
                text("page.html"),
                Highlighters.xml,
                copying,
            )
        }
    }

    @Test
    fun `pages loaded from a string have no location of their own`() {
        assertEquals(
            listOf(true, true, true, false, false),
            listOf(null, "", "about:blank", "file:///tmp/a.html", "http://127.0.0.1:1/").map(::isPageFromString),
        )
    }
}
