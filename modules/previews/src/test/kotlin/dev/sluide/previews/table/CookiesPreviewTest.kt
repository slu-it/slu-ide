package dev.sluide.previews.table

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CookiesPreviewTest {
    private val jar =
        "# domain\tpath\tname\tvalue\tdate\n" +
            "localhost\t/\tJSESSIONID\tabc123\t-1\n" +
            "example.com\t/api\ttheme\t\"dark\"\tFri, 01 Jan 2027 00:00:00 GMT\n" +
            "example.com\t/\tbroken\t\"open\t-1\n"

    @Test
    fun `the header loses its comment mark, quotes stay part of the values`() {
        val page = Jsoup.parse(CookiesPreview.render(jar, dark = false))

        assertEquals(listOf("domain", "path", "name", "value", "date"), page.select("thead th").map { it.text() })
        assertEquals(
            listOf("abc123", "\"dark\"", "\"open"),
            page.select("tbody tr").map { it.select("td")[3].text() },
        )
        assertEquals(listOf("0", "1", "2", "3"), page.select("tr").map { it.attr("data-line") })
    }

    @Test
    fun `a jar without the header mark shows its first line as header, an empty one says so`() {
        val page = Jsoup.parse(CookiesPreview.render("domain\tpath\nlocalhost\t/\n", dark = true))
        assertEquals(listOf("domain", "path"), page.select("thead th").map { it.text() })
        assertTrue(page.body().hasClass("dark"))

        assertEquals(
            "Empty file",
            Jsoup.parse(CookiesPreview.render("", dark = false)).selectFirst(".csv-note")?.text(),
        )
    }
}
