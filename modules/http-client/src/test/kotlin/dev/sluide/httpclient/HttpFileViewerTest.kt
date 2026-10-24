package dev.sluide.httpclient

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpFileViewerTest {
    @TempDir
    lateinit var dir: Path

    private val viewer = HttpFileViewer()

    @Test
    fun `opens http and rest files that are text`() {
        assertTrue(viewer.canOpen(dir.resolve("api.http").apply { writeText("GET http://x\n") }))
        assertTrue(viewer.canOpen(dir.resolve("api.REST").apply { writeText("GET http://x\n") }))
        assertFalse(viewer.canOpen(dir.resolve("bin.http").apply { writeBytes(byteArrayOf(0, 1, 2)) }))
        assertFalse(viewer.canOpen(dir.resolve("api.txt").apply { writeText("GET http://x\n") }))
        assertTrue(FileViewers(listOf(viewer), DefaultTextFileViewer()).showsDiffs(viewer))
    }
}
