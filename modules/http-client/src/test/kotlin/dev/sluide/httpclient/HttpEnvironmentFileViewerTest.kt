package dev.sluide.httpclient

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.ExtensionFileViewer
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.SourceFileViewer
import dev.sluide.httpclient.env.EnvironmentFiles
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpEnvironmentFileViewerTest {
    @TempDir
    lateinit var dir: Path

    private val viewer = HttpEnvironmentFileViewer()

    /** A viewer of other JSON files showing them as text, like the JSON preview of the previews. */
    private class JsonViewer :
        ExtensionFileViewer,
        SourceFileViewer {
        override val fileExtensions = setOf("json")
        override val sourceHighlighter = Highlighters.json

        override fun canOpen(file: Path) = file.extension.lowercase() in fileExtensions

        override fun open(file: Path): FileView = error("not needed")
    }

    @Test
    fun `opens the environment files by name, not other json files`() {
        val publicFile = dir.resolve(EnvironmentFiles.PUBLIC).apply { writeText("{}") }
        val privateFile = dir.resolve(EnvironmentFiles.PRIVATE).apply { writeText("{}") }
        val upper = dir.resolve("upper").apply { toFile().mkdir() }.resolve("HTTP-Client.Env.JSON")
        upper.writeText("{}")
        val other = dir.resolve("other.json").apply { writeText("{}") }
        val binary = dir.resolve("bin").apply { toFile().mkdir() }.resolve("http-client.env.json")
        binary.writeBytes(byteArrayOf(0, 1, 2))
        val json = JsonViewer()
        val viewers = FileViewers(listOf(json, viewer), DefaultTextFileViewer())

        assertTrue(viewer.canOpen(publicFile))
        assertTrue(viewer.canOpen(upper))
        assertFalse(viewer.canOpen(other))
        assertFalse(viewer.canOpen(binary))
        assertSame(viewer, viewers.viewerFor(publicFile))
        assertSame(viewer, viewers.viewerFor(privateFile))
        assertSame(json, viewers.viewerFor(other))
        assertTrue(viewers.showsDiffs(viewer))
    }
}
