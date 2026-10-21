package dev.sluide.fileviewers.markdown

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class MarkdownFileViewerTest {
    @TempDir
    lateinit var dir: Path

    private val viewer = MarkdownFileViewer()

    private fun file(name: String) = dir.resolve(name).apply { writeText("# Title\n") }

    @Test
    fun `opens Markdown text files only`() {
        val names = listOf("README.md", "notes.markdown", "LEGACY.MD", "docs.mdx", "notes.txt", "md")
        assertEquals(listOf("README.md", "notes.markdown", "LEGACY.MD"), names.filter { viewer.canOpen(file(it)) })
        assertFalse(viewer.canOpen(dir.resolve("missing.md")))
        assertFalse(viewer.canOpen(dir.resolve("binary.md").apply { writeBytes(byteArrayOf(0x61, 0x00)) }))
    }

    @Test
    fun `is chosen for Markdown files, before the default text viewer`() {
        val fallback = DefaultTextFileViewer()
        val viewers = FileViewers(listOf(fallback, viewer), fallback)

        assertSame(viewer, viewers.viewerFor(file("README.md")))
    }
}
