package dev.sluide.fileviewers

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CsvRendererTest {
    private fun cells(
        text: String,
        delimiter: Char = ',',
    ) = CsvRenderer.parse(text, delimiter).map { it.cells }

    @Test
    fun `plain and quoted fields`() {
        assertEquals(
            listOf(listOf("a", "b", ""), listOf("1", "x, y", "say \"hi\"")),
            cells("a,b,\n1,\"x, y\",\"say \"\"hi\"\"\"\n"),
        )
    }

    @Test
    fun `quoted line breaks stay in the field, rows know their first line`() {
        val rows = CsvRenderer.parse("name,note\r\nAda,\"first\r\nsecond\"\r\n\r\nBob,x", ',')

        assertEquals(
            listOf(listOf("name", "note"), listOf("Ada", "first\r\nsecond"), listOf("Bob", "x")),
            rows.map {
                it.cells
            },
        )
        assertEquals(listOf(0, 1, 4), rows.map { it.line })
    }

    @Test
    fun `the delimiter comes from the extension or the first line`() {
        assertEquals('\t', CsvRenderer.delimiter("TSV", "a,b"))
        assertEquals(',', CsvRenderer.delimiter("csv", "a,b;c"))
        assertEquals(';', CsvRenderer.delimiter("csv", "Name;Preis;Menge\nA;1,5;2"))
        assertEquals(listOf(listOf("a", "b c")), cells("a\tb c", '\t'))
    }

    @Test
    fun `the table has a header, every row has its source line and short rows are padded`() {
        val page = Jsoup.parse(CsvRenderer.render("x,y,z\n1,2\n<b>3</b>,4,5\n", ',', dark = false))

        assertEquals(listOf("x", "y", "z"), page.select("thead th").map { it.text() })
        assertEquals(
            listOf("1", "2", ""),
            page
                .select("tbody tr")
                .first()!!
                .select("td")
                .map { it.text() },
        )
        assertEquals("<b>3</b>", page.select("tbody tr")[1].selectFirst("td")!!.text())
        assertEquals(listOf("0", "1", "2"), page.select("tr").map { it.attr("data-line") })
    }

    @Test
    fun `at most ten thousand rows are shown`() {
        val text = "n\n" + (1..10_005).joinToString("\n")
        val page = Jsoup.parse(CsvRenderer.render(text, ',', dark = true))

        assertEquals(10_000, page.select("tbody tr").size)
        assertEquals("Showing the first 10,000 of 10,005 rows.", page.selectFirst(".csv-note")?.text())
        assertTrue(page.body().hasClass("dark"))
    }

    @Test
    fun `an empty file says so`() {
        assertEquals(
            "Empty file",
            Jsoup.parse(CsvRenderer.render("", ',', dark = false)).selectFirst(".csv-note")?.text(),
        )
    }

    @Test
    fun `a byte order mark does not belong to the first field`() {
        assertEquals(listOf(listOf("Name, first", "x")), cells("\uFEFF\"Name, first\",x"))
    }

    @Test
    fun `tables of semicolon files sort numbers with decimal commas`() {
        val semicolon = Jsoup.parse(CsvRenderer.render("a;b\n1,5;2", ';', dark = false))
        val comma = Jsoup.parse(CsvRenderer.render("a,b\n1,2", ',', dark = false))

        assertEquals(",", semicolon.selectFirst("table")?.attr("data-decimal"))
        assertEquals(".", comma.selectFirst("table")?.attr("data-decimal"))
    }
}
