package dev.sluide.fileviewers.preview

import dev.sluide.fileviewers.HighlightSpan
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TokenStyle
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlPageTest {
    @Test
    fun `a page carries its styles in the head and its scripts after the content`() {
        val html =
            HtmlPage.render(
                true,
                listOf("a {}", "b {}"),
                listOf("one()", "two()"),
                listOf("csv"),
            ) { append("<p>x</p>") }
        val page = Jsoup.parse(html)

        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertEquals("utf-8", page.selectFirst("head meta[charset]")?.attr("charset"))
        assertEquals(listOf("a {}", "b {}"), page.select("head style").map { it.data().trim() })
        assertEquals(listOf("dark", "csv"), page.body().classNames().toList())
        assertEquals(listOf("p", "script", "script"), page.body().children().map { it.tagName() })
        assertEquals(listOf("one()", "two()"), page.select("body script").map { it.data().trim() })
        assertEquals("<body>", Regex("<body[^>]*>").find(HtmlPage.render(false, emptyList(), emptyList()) {})?.value)
    }

    @Test
    fun `highlighted code is written as plain parts and tokens`() {
        val parts = mutableListOf<String>()
        val highlighter = SyntaxHighlighter { listOf(HighlightSpan(0, 3, TokenStyle.KEYWORD)) }

        val token = { text: String, styleClass: String -> parts += "$styleClass:$text" }
        writeHighlighted("val x", highlighter, { parts += "text:$it" }, token)
        writeHighlighted("plain", null, { parts += "text:$it" }) { _, _ -> error("no tokens") }

        assertEquals(listOf("text:", "keyword:val", "text: x", "text:plain"), parts)
    }
}
