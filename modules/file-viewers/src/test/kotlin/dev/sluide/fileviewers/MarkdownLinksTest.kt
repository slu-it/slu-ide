package dev.sluide.fileviewers

import java.net.URI
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkdownLinksTest {
    private val folder = Path.of("/projects/app/docs")

    @Test
    fun `web links open as they are`() {
        for (href in listOf("https://example.com/a?b=1#c", "http://example.com", "mailto:someone@example.com")) {
            assertEquals(LinkTarget.WebPage(URI(href)), resolveLink(href, folder), href)
        }
    }

    @Test
    fun `relative links are files next to the Markdown file, without fragment or query`() {
        assertEquals(LinkTarget.File(folder.resolve("other.md")), resolveLink("other.md#part", folder))
        assertEquals(
            LinkTarget.File(Path.of("/projects/app/src/Main.kt")),
            resolveLink("../src/Main.kt?plain=1", folder),
        )
        assertEquals(LinkTarget.File(folder.resolve("my notes.md")), resolveLink("my%20notes.md", folder))
        assertEquals(LinkTarget.File(folder.resolve("my notes.md")), resolveLink(" my notes.md ", folder))
    }

    @Test
    fun `file links are files`() {
        assertEquals(LinkTarget.File(Path.of("/tmp/x.md")), resolveLink("file:///tmp/x.md#top", folder))
    }

    @Test
    fun `anchors, other schemes and broken links lead nowhere`() {
        for (href in listOf("#section", "", "javascript:alert(1)", "ftp://example.com/x", "?only=query", "a[b")) {
            assertNull(resolveLink(href, folder), href)
        }
    }
}
