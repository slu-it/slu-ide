package dev.sluide.fileviewers.notebook

import org.jsoup.Jsoup
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotebookRendererTest {
    @TempDir
    lateinit var dir: Path

    private fun render(json: String) = Jsoup.parse(NotebookRenderer.render(json, dir, dark = false))

    private val notebook =
        """
        {
          "nbformat": 4, "nbformat_minor": 5,
          "metadata": {"language_info": {"name": "python"}},
          "cells": [
            {"cell_type": "markdown", "source": ["# Analysis\n", "Energy ${'$'}E=mc^2${'$'} ![chart](attachment:c.png)"],
             "attachments": {"c.png": {"image/png": "iVBORw0K\nGgo="}}},
            {"cell_type": "code", "execution_count": 3, "source": "import pandas as pd\nprint('hi')",
             "outputs": [
               {"output_type": "stream", "name": "stdout", "text": ["hi\n"]},
               {"output_type": "execute_result", "execution_count": 3,
                "data": {"text/html": "<table><tr><td>1</td></tr></table><script>alert(1)</script>", "text/plain": "df"}},
               {"output_type": "display_data", "data": {"image/png": "AQID\n", "text/plain": "<Figure>"}},
               {"output_type": "display_data", "data": {"image/svg+xml": ["<svg xmlns='http://www.w3.org/2000/svg'/>"]}},
               {"output_type": "error", "ename": "ValueError", "evalue": "bad",
                "traceback": ["\u001b[0;31mValueError\u001b[0m: bad"]}
             ]},
            {"cell_type": "code", "execution_count": null, "source": [], "outputs": []}
          ]
        }
        """.trimIndent()

    @Test
    fun `markdown cells are rendered with math and attached images`() {
        val page = render(notebook)

        assertEquals("Analysis", page.selectFirst(".cell.markdown h1")?.text())
        assertEquals("E=mc^2", page.selectFirst(".cell.markdown .math")?.text())
        assertEquals("data:image/png;base64,iVBORw0KGgo=", page.selectFirst(".cell.markdown img")?.attr("src"))
    }

    @Test
    fun `code cells are highlighted with their execution count`() {
        val cells = render(notebook).select(".cell.code")

        assertEquals(listOf("In [3]:", "In [ ]:"), cells.map { it.selectFirst(".prompt")?.text() })
        assertEquals("import", cells[0].selectFirst("pre code span.keyword")?.text())
    }

    @Test
    fun `outputs show their richest form, sanitised`() {
        val outputs = assertNotNull(render(notebook).select(".cell.code").first()).select(".output")

        assertEquals("hi", outputs[0].text())
        assertTrue(outputs[0].hasClass("stdout"))
        assertEquals("1", outputs[1].selectFirst("td")?.text())
        assertNull(outputs[1].selectFirst("script"))
        assertEquals("data:image/png;base64,AQID", outputs[2].selectFirst("img")?.attr("src"))
        assertTrue(assertNotNull(outputs[3].selectFirst("img")).attr("src").startsWith("data:image/svg+xml;base64,"))
        assertEquals("ValueError: bad", outputs[4].text())
        assertTrue(outputs[4].hasClass("error"))
    }

    @Test
    fun `the language comes from the kernel when the notebook has no language info`() {
        val page =
            render(
                """{"metadata": {"kernelspec": {"language": "kotlin"}}, "cells": [{"cell_type": "code", "source": "val x = 1"}]}""",
            )

        assertEquals("val", page.selectFirst("code span.keyword")?.text())
    }

    @Test
    fun `a broken file says so`() {
        val error = assertNotNull(render("{ not json").selectFirst(".notebook-error"))
        assertTrue(error.text().startsWith("Not a valid notebook:"))
    }

    @Test
    fun `notebooks of an unexpected shape are no valid notebooks`() {
        val shapes =
            listOf(
                """{"cells":[{"cell_type":"markdown","source":{"a":1}}]}""",
                """{"cells":[{"cell_type":"code","source":["x",{}]}]}""",
                """{"metadata":{"language_info":{"name":{}}},"cells":[]}""",
                "[]",
                "42",
                "{}",
                """{"cells":"x"}""",
                "",
            )
        for (json in shapes) {
            val error = assertNotNull(render(json).selectFirst(".notebook-error"), json)
            assertTrue(error.text().startsWith("Not a valid notebook: "), json)
        }
    }

    @Test
    fun `raw cells show their text, Markdown outputs are rendered`() {
        val json =
            """{"cells":[{"cell_type":"raw","source":["<b>raw</b>\n","*text*"]},
            {"cell_type":"code","source":"x","outputs":[{"output_type":"display_data",
              "data":{"text/markdown":"**bold** <script>alert(1)</script>","text/plain":"<Markdown>"}}]}]}"""
        val page = render(json)

        assertEquals("<b>raw</b>\n*text*", page.selectFirst(".cell.raw pre")?.wholeText())
        val output = assertNotNull(page.selectFirst(".output"))
        assertEquals("bold", output.selectFirst("strong")?.text())
        assertNull(output.selectFirst("script"))
    }

    @Test
    fun `a notebook without cells is empty, not broken`() {
        val page = render("""{"cells":[]}""")

        assertNull(page.selectFirst(".notebook-error"))
        assertEquals(0, page.select(".cell").size)
    }

    @Test
    fun `HTML outputs that sanitise to nothing fall back to plain text`() {
        val json =
            """{"cells":[{"cell_type":"code","source":"fig","outputs":[{"output_type":"display_data",
            "data":{"text/html":"<div></div><script>Plotly.newPlot()</script>","text/plain":"Figure()"}}]}]}"""

        assertEquals("Figure()", render(json).selectFirst(".output")?.text())
    }

    @Test
    fun `text around and without elements in HTML outputs and Markdown cells is kept`() {
        val json =
            """{"cells":[{"cell_type":"markdown","source":"<section>kept</section>"},
            {"cell_type":"code","source":"x","outputs":[
              {"output_type":"execute_result","data":{"text/html":"Total: <b>42</b>","text/plain":"42"}},
              {"output_type":"display_data","data":{"text/html":"done","text/plain":"<IPython.core.display.HTML>"}}]}]}"""
        val page = render(json)

        // The sanitiser drops the section, its text stays.
        assertEquals("kept", page.selectFirst(".cell.markdown")?.text())
        assertEquals(listOf("Total: 42", "done"), page.select(".output").map { it.text() })
    }

    @Test
    fun `attachments with encoded names, in raw HTML too`() {
        val json =
            """{"cells":[{"cell_type":"markdown","source":"![a](attachment:my%20plot.png) <img src=\"attachment:b.png\">",
            "attachments":{"my plot.png":{"image/png":"AQ=="},"b.png":{"image/png":"Ag=="}}}]}"""

        assertEquals(
            listOf(
                "data:image/png;base64,AQ==",
                "data:image/png;base64,Ag==",
            ),
            render(json).select("img").map {
                it.attr("src")
            },
        )
    }
}
