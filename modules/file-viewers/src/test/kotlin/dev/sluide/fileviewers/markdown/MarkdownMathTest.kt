package dev.sluide.fileviewers.markdown

import dev.sluide.fileviewers.preview.Vendor
import org.jsoup.Jsoup
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownMathTest {
    @TempDir
    lateinit var dir: Path

    private fun render(markdown: String) = Jsoup.parse(MarkdownRenderer.render(markdown, dir))

    private fun math(markdown: String) =
        render(markdown).select(".math").map {
            (if (it.hasClass("display")) "D:" else "I:") +
                it.wholeText()
        }

    @Test
    fun `inline math`() {
        assertEquals(listOf("I:a^2 + b^2", "I:x_1"), math("Pythagoras: \$a^2 + b^2\$, and \$x_1\$."))
        // Emphasis characters inside math stay TeX.
        assertEquals(listOf("I:a_b * c_d"), math("\$a_b * c_d\$"))
    }

    @Test
    fun `dollars that are no math`() {
        for (text in listOf("It costs \$5 and \$6.", "\$ a\$", "\$a \$", "Escaped \\\$a\$", "Lone \$", "a \$\$ b")) {
            assertEquals(emptyList(), math(text), text)
        }
    }

    @Test
    fun `display math inline, in blocks and in math fences`() {
        val page = "\$\$E = mc^2\$\$\n\n\$\$\n\\int_0^1 x\\,dx\n= 1/2\n\$\$\n\n```math\n\\sum_i i\n```\n\nAfter\n"

        assertEquals(listOf("D:E = mc^2", "D:\\int_0^1 x\\,dx\n= 1/2", "D:\\sum_i i"), math(page))
        assertEquals("After", render(page).select("p").last()?.text())
    }

    @Test
    fun `display blocks carry their source line`() {
        val blocks = render("Text\n\n\$\$\nx\n\$\$\n").select("div.math")

        assertEquals("2", blocks.single().attr("data-line"))
    }

    @Test
    fun `KaTeX is only included in pages with math`() {
        val withMath = MarkdownRenderer.render("\$x\$", dir)
        val without = MarkdownRenderer.render("no math", dir)

        assertTrue(Vendor.katexJs in withMath && "sluide.renderMath()" in withMath)
        assertTrue(Vendor.katexCss in withMath && "data:font/woff;base64," in withMath)
        assertFalse("url(fonts/" in withMath)
        assertFalse(Vendor.katexJs in without || Vendor.katexCss in without)
    }

    @Test
    fun `display math within a paragraph keeps the paragraph whole`() {
        val paragraph = render("Energy \$\$E=mc^2\$\$ here").select("p")

        assertEquals(1, paragraph.size)
        assertEquals("Energy E=mc^2 here", paragraph.text())
        assertEquals("math display", paragraph.select(".math").attr("class"))
    }

    @Test
    fun `backslashes before the closing dollar`() {
        // `\\` is a TeX line break, so the dollar after it closes the math; `\$` is an escaped dollar.
        assertEquals(listOf("I:a \\\\"), math("\$a \\\\\$ b"))
        assertEquals(listOf("I:a \\\$ b"), math("\$a \\\$ b\$"))
    }
}
