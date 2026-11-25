package dev.sluide.pdf.view

import dev.sluide.pdf.HostilePdfs
import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.PageText
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PageTextsTest {
    private val executor = ManualExecutor()
    private val fx = ManualFx()
    private val worker =
        PdfWorker(executor, fx)

    @AfterTest
    fun dispose() {
        worker.dispose()
        executor.runAll()
    }

    private fun texts(
        file: Path,
        capacity: Int = PageTexts.CAPACITY,
    ): PageTexts {
        worker.reopen(file, null, { it.pageCount }) {}
        run()
        return PageTexts(worker, capacity)
    }

    private fun run() {
        executor.runAll()
        fx.runAll()
    }

    private fun PageTexts.text(page: Int): PageText {
        var result: PageText? = null
        textOf(page) { result = it }
        run()
        return checkNotNull(result)
    }

    @Test
    fun `gives the text of a page with its boxes on the FX runner`() {
        val texts = texts(TestPdfs.text)
        var result: PageText? = null
        texts.textOf(0) { result = it }
        executor.runAll()
        assertEquals(null, result)
        fx.runAll()
        val text = checkNotNull(result)
        assertTrue(text.text.startsWith(TestPdfs.FIRST_LINE))
        assertEquals(text.text.length, text.boxes.size)
        assertTrue(text.boxes.first() != null)
    }

    @Test
    fun `extracts a page once and answers later requests from the cache at once`() {
        val texts = texts(TestPdfs.manyPages)
        val first = texts.text(3)
        var second: PageText? = null
        texts.textOf(3) { second = it }
        assertSame(first, second)
    }

    @Test
    fun `requests for a page whose text is on its way share one extraction`() {
        val texts = texts(TestPdfs.manyPages)
        val results = mutableListOf<PageText>()
        texts.textOf(2) { results += it }
        texts.textOf(2) { results += it }
        run()
        assertEquals(2, results.size)
        assertSame(results[0], results[1])
    }

    @Test
    fun `keeps the most recently used pages up to its capacity`() {
        val texts = texts(TestPdfs.manyPages, capacity = 2)
        val zero = texts.text(0)
        val one = texts.text(1)
        texts.text(0)
        texts.text(2)
        assertSame(zero, texts.text(0))
        assertNotSame(one, texts.text(1))
    }

    @Test
    fun `gives the plain text of a page, equal to its text with boxes, and keeps it`() {
        val texts = texts(TestPdfs.manyPages)
        var plain: String? = null
        texts.plainTextOf(4) { plain = it }
        run()
        assertEquals(texts.text(4).text, plain)
        assertTrue(checkNotNull(plain).startsWith("Page 5\n"))
        var again: String? = null
        texts.plainTextOf(4) { again = it }
        assertSame(plain, again)
    }

    @Test
    fun `requests for a plain text on its way share one extraction`() {
        val texts = texts(TestPdfs.manyPages)
        val results = mutableListOf<String>()
        texts.plainTextOf(1) { results += it }
        texts.plainTextOf(1) { results += it }
        run()
        assertEquals(2, results.size)
        assertSame(results[0], results[1])
    }

    @Test
    fun `a text document that did not open answers with empty texts`() {
        worker.reopen(TestPdfs.notPdf, null, { it.pageCount }) {}
        run()
        val texts = PageTexts(worker)
        var text: PageText? = null
        var plain: String? = null
        texts.textOf(0) { text = it }
        texts.plainTextOf(0) { plain = it }
        run()
        assertEquals("", text?.text)
        assertEquals("", plain)
    }

    @Test
    fun `a page that cannot be read gives an empty text`() {
        val texts = texts(HostilePdfs.brokenPages)
        val text = texts.text(5)
        assertEquals("", text.text)
        assertEquals(emptyList(), text.boxes)
        var plain: String? = null
        texts.plainTextOf(5) { plain = it }
        run()
        assertEquals("", plain)
    }

    @Test
    fun `clear forgets the texts and the requests on their way`() {
        val texts = texts(TestPdfs.manyPages)
        val first = texts.text(0)
        var plain: String? = null
        texts.plainTextOf(0) { plain = it }
        run()
        val pending = mutableListOf<PageText>()
        texts.textOf(1) { pending += it }
        texts.plainTextOf(2) {}
        texts.clear()
        worker.reopen(TestPdfs.manyPages, null, { it.pageCount }) {}
        run()
        assertNotSame(first, texts.text(0))
        var plainAgain: String? = null
        texts.plainTextOf(0) { plainAgain = it }
        run()
        assertNotSame(plain, plainAgain)
        val after = mutableListOf<PageText>()
        texts.textOf(1) { after += it }
        run()
        assertEquals(1, after.size)
        assertEquals(emptyList(), pending)
        var plainAfter: String? = null
        texts.plainTextOf(2) { plainAfter = it }
        run()
        assertTrue(checkNotNull(plainAfter).startsWith("Page 3\n"))
    }
}
