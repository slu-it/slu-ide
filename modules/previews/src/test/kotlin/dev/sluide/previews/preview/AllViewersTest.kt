package dev.sluide.previews.preview

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.previews.json.JsonFileViewer
import dev.sluide.previews.markdown.MarkdownFileViewer
import dev.sluide.previews.pem.PemFileViewer
import dev.sluide.previews.workflow.WorkflowFileViewer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which viewer opens a file among all viewers of the core and the previews, as Spring finds them ([allViewers]): their
 * rank decides, not the order of the beans.
 */
class AllViewersTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the scan finds the viewers of the core and of the previews`() {
        val names = allViewers.map { it::class.simpleName }

        assertTrue("EnvFileViewer" in names && "KotlinFileViewer" in names, "core viewers")
        assertTrue(allViewers.any { it is MarkdownFileViewer } && allViewers.any { it is PemFileViewer }, "previews")
        assertEquals(1, allViewers.count { it is DefaultTextFileViewer })
    }

    @Test
    fun `among all viewers, env files open as env unless their extension names another language`() {
        for (viewers in allViewersInEitherOrder()) {
            for (name in listOf(".env", ".env.local", ".env.production", "prod.env")) {
                val env = dir.resolve(name).apply { writeText("A=1\n") }
                assertEquals("EnvFileViewer", viewerNameFor(viewers, env), name)
            }
            val json = dir.resolve(".env.json").apply { writeText("{}\n") }
            assertTrue(viewers.viewerFor(json) is JsonFileViewer, "an extension wins over a name pattern")
        }
    }

    @Test
    fun `among all viewers, YAML directly in a workflows folder of a github folder opens as a workflow`() {
        val workflows = dir.resolve(".github/workflows").createDirectories()
        val elsewhere =
            listOf(
                dir.resolve("config.yml"),
                dir.resolve(".github/dependabot.yml"),
                dir.resolve("workflows").createDirectories().resolve("ci.yml"),
                workflows.resolve("sub").createDirectories().resolve("ci.yml"),
            )
        for (viewers in allViewersInEitherOrder()) {
            for (name in listOf("ci.yml", "release.YAML")) {
                val file = workflows.resolve(name).apply { writeText("on: push\n") }
                assertTrue(viewers.viewerFor(file) is WorkflowFileViewer, name)
            }
            val notes = workflows.resolve("notes.txt").apply { writeText("x\n") }
            assertTrue(viewers.viewerFor(notes) is DefaultTextFileViewer, "only YAML is a workflow")
            for (file in elsewhere) {
                assertEquals("YamlFileViewer", viewerNameFor(viewers, file.apply { writeText("a: 1\n") }), "$file")
            }
            // FileViewers picks it by the path for diffs by name too (its FileViewersTest); it shows them as YAML text.
            assertTrue(viewers.showsDiffs(allViewers.single { it is WorkflowFileViewer }))
        }
    }

    @Test
    fun `among all viewers, PEM files open as PEM details`() {
        val pem = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"
        for (viewers in allViewersInEitherOrder()) {
            for (name in listOf("cert.pem", "site.crt", "ca.cer", "tls.key")) {
                assertTrue(viewers.viewerFor(dir.resolve(name).apply { writeText(pem) }) is PemFileViewer, name)
            }
            val raw = dir.resolve("raw.key").apply { writeText("not PEM\n") }
            assertTrue(viewers.viewerFor(raw) is DefaultTextFileViewer, "a key without PEM text is plain text")
        }
    }
}
