package dev.sluide.todos.markdown

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkdownLinksTest {
    @TempDir
    lateinit var dir: Path

    @ParameterizedTest
    @CsvSource(
        "'- [ ] a',2,'- [x] a'",
        "'- [x] a',2,'- [ ] a'",
        "'- [X] a',2,'- [ ] a'",
        "'- [ ] a',0,",
        "'- [-] a',2,",
        "'- [ ] a',99,",
        "'- [ ] a',-1,",
        "'[ ',0,",
    )
    fun `toggleTask flips the marker at the offset`(
        source: String,
        offset: Int,
        expected: String?,
    ) = assertEquals(expected, toggleTask(source, offset))

    @ParameterizedTest
    @CsvSource(
        "https://example.com/a?b=1#c,web",
        "http://example.com,web",
        "mailto:me@example.com,web",
        "file:///etc/passwd,none",
        "javascript:alert(1),none",
        "notes/a%20b.md#top,file",
        "./a.md?x=1,file",
        "src/../a.md,file",
        "../outside.md,none",
        "src/../../outside.md,none",
        "/etc/passwd,none",
        "#anchor,none",
        "'',none",
    )
    fun `linkTarget routes web links and project files`(
        href: String,
        kind: String,
    ) {
        val target = linkTarget(href, dir)
        when (kind) {
            "web" -> assertEquals(LinkTarget.Web(URI(href)), target)
            "file" -> assertEquals(LinkTarget.File::class, target::class)
            else -> assertEquals(LinkTarget.None, target)
        }
    }

    @Test
    fun `linkTarget resolves a file path and decodes it`() {
        assertEquals(LinkTarget.File(dir.resolve("notes/a b.md")), linkTarget("notes/a%20b.md#top", dir))
        assertEquals(LinkTarget.File(dir.resolve("x.md")), linkTarget(dir.resolve("x.md").toString(), dir))
    }

    @Test
    fun `linkTarget without a project folder only opens web links`() {
        assertEquals(LinkTarget.None, linkTarget("a.md", null))
        assertEquals(LinkTarget.Web(URI("https://e.com")), linkTarget("https://e.com", null))
    }

    @Test
    fun `imageSource accepts web urls and existing files under the project`() {
        val png = Files.write(dir.resolve("p.png"), byteArrayOf(1))
        assertEquals("https://e.com/a.png", imageSource("https://e.com/a.png", null))
        assertEquals(png.toUri().toString(), imageSource("p.png", dir))
        assertEquals(png.toUri().toString(), imageSource(png.toString(), dir))
        assertNull(imageSource("missing.png", dir))
        assertNull(imageSource("p.png", null))
        assertNull(imageSource("../p.png", dir.resolve("sub")))
        assertNull(imageSource("file:///etc/passwd", dir))
        assertNull(imageSource("data:image/png;base64,AA", dir))
    }
}
