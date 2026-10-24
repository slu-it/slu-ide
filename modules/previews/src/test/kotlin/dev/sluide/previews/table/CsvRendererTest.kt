package dev.sluide.previews.table

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `without quotes, quotes are part of the fields`() {
        assertEquals(
            listOf(listOf("\"a\"", "\"b"), listOf("c", "d\"\"")),
            CsvRenderer.parse("\"a\"\t\"b\nc\td\"\"\n", '\t', quotes = false).map { it.cells },
        )
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
        val rows = page.select("tbody tr:not(.csv-first)")
        assertEquals(listOf("1", "2", ""), assertNotNull(rows.first()).select("td").map { it.text() })
        assertEquals("<b>3</b>", rows[1].selectFirst("td")?.text())
        assertEquals(listOf("0", "1", "2"), page.select("tbody tr").map { it.attr("data-line") })
        assertEquals("0", page.selectFirst("tr.csv-labels")?.attr("data-line"))
    }

    @Test
    fun `at most ten thousand rows are shown`() {
        val text = "n\n" + (1..10_005).joinToString("\n")
        val page = Jsoup.parse(CsvRenderer.render(text, ',', dark = true))

        assertEquals(10_000, page.select("tbody tr:not(.csv-first)").size)
        assertEquals(
            "Showing the first 10,000 of 10,005 rows. Search covers only the rows shown.",
            page.selectFirst(".csv-note")?.text(),
        )
        assertTrue(page.body().hasClass("dark"))
    }

    @Test
    fun `the row limit can be lowered`() {
        val page = Jsoup.parse(CsvRenderer.render("n\n1\n2\n3\n4\n", ',', dark = false, maxRows = 2))

        assertEquals(2, page.select("tbody tr:not(.csv-first)").size)
        assertEquals(
            "Showing the first 2 of 4 rows. Search covers only the rows shown.",
            page.selectFirst(".csv-note")?.text(),
        )
    }

    @Test
    fun `without a header the first row counts in the note on cut-off files`() {
        val text = (1..10_005).joinToString("\n")
        val page = Jsoup.parse(CsvRenderer.render(text, ',', dark = false))

        assertEquals(10_001, page.select("tbody tr").size)
        val note = assertNotNull(page.selectFirst(".csv-note"))
        assertEquals("Showing the first 10,001 of 10,005 rows. Search covers only the rows shown.", note.text())
        assertEquals("10001", note.attr("data-shown"))
        assertEquals("10005", note.attr("data-records"))
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

    private fun rows(vararg lines: String): List<CsvRenderer.Row> {
        val delimiter = if (';' in lines[0]) ';' else ','
        return CsvRenderer.parse(lines.joinToString("\n"), delimiter)
    }

    @Test
    fun `a number column under a text title means a header`() {
        assertTrue(hasHeader(rows("name,age", "Bob,7", "Ada,36"), decimalComma = false))
    }

    @Test
    fun `rows of numbers only have no header`() {
        assertFalse(hasHeader(rows("1,2", "3,4", "5,6"), decimalComma = false))
    }

    @Test
    fun `a date column votes like a number column`() {
        assertTrue(hasHeader(rows("when,what", "2024-01-31,a", "01.02.2024,b", "2/28/2024,c"), decimalComma = false))
        assertFalse(hasHeader(rows("2024-01-01,a", "2024-01-02T10:00,b"), decimalComma = false))
    }

    @Test
    fun `all text, a tie, a single row or no votes mean a header`() {
        assertTrue(hasHeader(rows("a,b", "c,d"), decimalComma = false))
        // Column 0 votes against (number over numbers), column 1 for (text over numbers).
        assertTrue(hasHeader(rows("1,x", "2,3"), decimalComma = false))
        assertTrue(hasHeader(rows("1,2"), decimalComma = false))
        assertTrue(hasHeader(rows(",2", "1,"), decimalComma = false))
    }

    @Test
    fun `numbers with decimal commas count as numbers`() {
        assertTrue(hasHeader(rows("Name;Preis", "A;1.234,5", "B;2,75"), decimalComma = true))
        assertFalse(hasHeader(rows("X;1,5", "A;1.234,5", "B;2,75"), decimalComma = true))
    }

    @Test
    fun `the guessed header gives the labels, a table without one is labelled with letters`() {
        val guessed = Jsoup.parse(CsvRenderer.render("name,age\nBob,7\n", ',', dark = false))
        val table = assertNotNull(guessed.selectFirst("table"))
        assertEquals("true", table.attr("data-header"))
        assertTrue(table.hasClass("has-header"))
        assertNotNull(guessed.selectFirst("input.csv-header-toggle[checked]"))
        assertEquals(listOf("name", "age"), guessed.select("tr.csv-labels th").map { it.text() })
        assertEquals(listOf("name", "age"), guessed.select("tr.csv-first td").map { it.text() })

        val numbers = Jsoup.parse(CsvRenderer.render("1,2,3\n4,5,6\n", ',', dark = false))
        val plain = assertNotNull(numbers.selectFirst("table"))
        assertEquals("false", plain.attr("data-header"))
        assertFalse(plain.hasClass("has-header"))
        assertNotNull(numbers.selectFirst("input.csv-header-toggle:not([checked])"))
        assertEquals(listOf("A", "B", "C"), numbers.select("tr.csv-labels th").map { it.text() })
        assertFalse(assertNotNull(numbers.selectFirst("tr.csv-labels")).hasAttr("data-line"))
        assertEquals(listOf("0", "1"), numbers.select("tbody tr").map { it.attr("data-line") })
        assertEquals(3, numbers.select("tr.csv-filters input.csv-filter").size)
    }

    @Test
    fun `columns past Z are labelled AA, AB and so on`() {
        assertEquals(
            listOf("A", "Z", "AA", "AZ", "BA", "ZZ", "AAA"),
            listOf(0, 25, 26, 51, 52, 701, 702).map(CsvRenderer::letters),
        )
    }

    @Test
    fun `the state's header choice beats the guess`() {
        val page =
            Jsoup.parse(
                CsvRenderer.render("name,age\nBob,7\n", ',', dark = false, state = TableState(header = false)),
            )

        assertEquals("false", page.selectFirst("table")?.attr("data-header"))
        assertEquals(listOf("A", "B"), page.select("tr.csv-labels th").map { it.text() })
    }

    @Test
    fun `a fixed header has no checkbox`() {
        val page =
            Jsoup.parse(
                CsvRenderer.render(
                    "1,2\n3,4\n",
                    ',',
                    dark = false,
                    fixedHeader = true,
                    state = TableState(header = false),
                ),
            )

        assertNull(page.selectFirst("input.csv-header-toggle"))
        assertEquals("true", page.selectFirst("table")?.attr("data-header"))
        assertEquals(listOf("1", "2"), page.select("tr.csv-labels th").map { it.text() })
        assertNull(TableState.parse(assertNotNull(page.selectFirst("table")).attr("data-state")).header)
    }

    @Test
    fun `the table carries its state, restored filters show their text`() {
        val state = TableState(header = true, sortColumn = 1, descending = true, filters = listOf("a\"<b>", ">100"))
        val page = Jsoup.parse(CsvRenderer.render("x,y\n1,2\n", ',', dark = false, state = state))

        assertEquals(state, TableState.parse(assertNotNull(page.selectFirst("table")).attr("data-state")))
        assertEquals(listOf("a\"<b>", ">100"), page.select("input.csv-filter").map { it.attr("value") })
    }

    @Test
    fun `a restored sort column or filters beyond the columns are left out`() {
        val state = TableState(sortColumn = 2, filters = listOf("a", "b", "c"))
        val page = Jsoup.parse(CsvRenderer.render("x,y\n1,2\n", ',', dark = false, state = state))

        assertEquals(
            TableState(filters = listOf("a", "b")),
            TableState.parse(assertNotNull(page.selectFirst("table")).attr("data-state")),
        )
    }

    @Test
    fun `table state JSON round trips, malformed parts keep their defaults`() {
        val state = TableState(header = false, sortColumn = 3, descending = true, filters = listOf("", "x"))
        assertEquals(state, TableState.parse(state.json()))
        assertEquals(TableState(), TableState.parse(TableState().json()))

        assertEquals(TableState(), TableState.parse("not json"))
        assertEquals(TableState(), TableState.parse("[1]"))
        assertEquals(
            TableState(filters = listOf("", "y")),
            TableState.parse("""{"header":"yes","sortColumn":-1,"descending":1,"filters":[3,"y"]}"""),
        )
    }
}
