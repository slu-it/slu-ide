package dev.sluide.fileviewers.preview

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.json.JsonFileViewer
import dev.sluide.fileviewers.markdown.MarkdownFileViewer
import dev.sluide.fileviewers.pem.PemFileViewer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
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
