package dev.sluide.pdf.view

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.PdfDocument
import dev.sluide.pdf.engine.PdfOpenResult
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfWorkerTest {
    private val executor = ManualExecutor()
    private val fx = ManualFx()
    private val opened = mutableListOf<PdfDocument>()
    private val closed = mutableListOf<PdfDocument>()
    private val worker =
        PdfWorker(
            executor,
            fx,
            open = { file, password ->
                PdfDocument.open(file, password).also { if (it is PdfOpenResult.Opened) opened += it.document }
            },
            close = { document ->
                closed += document
                document.close()
            },
        )

    @AfterTest
    fun closeAll() = opened.forEach(PdfDocument::close)

    private fun openText(): Opening<Int>? {
        var result: Opening<Int>? = null
        worker.reopen(TestPdfs.text, null, { it.pageCount }) { result = it }
        executor.runAll()
        fx.runAll()
        return result
    }

    private fun record(
        into: MutableList<String>,
        name: String,
        priority: WorkPriority,
        tag: Any? = null,
    ) = worker.submit(priority, { into += name }, {}, tag = tag)

    @Test
    fun `reopen reads the opened document on the worker and hands the value to the FX thread`() {
        var result: Opening<Int>? = null
        worker.reopen(TestPdfs.manyPages, null, { it.pageCount }) { result = it }
        executor.runAll()
        assertEquals(null, result)
        fx.runAll()
        assertEquals(Opening.Opened(TestPdfs.MANY_PAGES), result)
    }

    @Test
    fun `reopen reports why a document did not open, with the password given`() {
        var result: Opening<Int>? = null
        worker.reopen(TestPdfs.userPassword, null, { it.pageCount }) { result = it }
        executor.runAll()
        fx.runAll()
        assertEquals(Opening.Failed(PdfOpenResult.NeedsPassword), result)
        worker.reopen(TestPdfs.userPassword, TestPdfs.USER_PASSWORD, { it.pageCount }) { result = it }
        executor.runAll()
        fx.runAll()
        assertEquals(Opening.Opened(1), result)
    }

    @Test
    fun `a document whose read fails is closed and reported invalid`() {
        var result: Opening<Int>? = null
        worker.reopen(TestPdfs.text, null, { throw IllegalStateException("no outline") }) { result = it }
        executor.runAll()
        fx.runAll()
        assertEquals(Opening.Failed(PdfOpenResult.Invalid("no outline")), result)
        assertEquals(opened, closed)
        val ran = mutableListOf<String>()
        record(ran, "after", WorkPriority.VISIBLE)
        executor.runAll()
        assertEquals(emptyList(), ran)
    }

    @Test
    fun `reopen closes the old document before it opens the new one`() {
        openText()
        val first = opened.single()
        openText()
        assertEquals(listOf(first), closed)
        assertTrue(first.closed)
        assertFalse(opened.last().closed)
    }

    @Test
    fun `queued jobs run by priority, first come first served within one`() {
        openText()
        val ran = mutableListOf<String>()
        record(ran, "search", WorkPriority.SEARCH)
        record(ran, "visible 1", WorkPriority.VISIBLE)
        record(ran, "near", WorkPriority.NEAR)
        record(ran, "text", WorkPriority.TEXT)
        record(ran, "visible 2", WorkPriority.VISIBLE)
        record(ran, "thumbnail", WorkPriority.THUMBNAIL)
        executor.runAll()
        assertEquals(listOf("visible 1", "visible 2", "near", "thumbnail", "text", "search"), ran)
    }

    @Test
    fun `first come first served holds for many jobs of one priority between others`() {
        openText()
        val ran = mutableListOf<String>()
        repeat(8) {
            record(ran, "search $it", WorkPriority.SEARCH)
            record(ran, "visible $it", WorkPriority.VISIBLE)
        }
        executor.runAll()
        assertEquals((0 until 8).map { "visible $it" } + (0 until 8).map { "search $it" }, ran)
    }

    @Test
    fun `cancelQueued never drops the opening of a document`() {
        worker.reopen(TestPdfs.text, null, { it.pageCount }) {}
        worker.cancelQueued { true }
        executor.runAll()
        assertEquals(1, opened.size)
    }

    @Test
    fun `a read that fails without a message is reported by its kind`() {
        var result: Opening<Int>? = null
        worker.reopen(TestPdfs.text, null, { throw IllegalStateException() }) { result = it }
        executor.runAll()
        fx.runAll()
        assertEquals(Opening.Failed(PdfOpenResult.Invalid("IllegalStateException")), result)
    }

    @Test
    fun `by default the document is closed and a failed job without onError is logged`() {
        val documents = mutableListOf<PdfDocument>()
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() {}

                override fun close() {}
            }
        val logger = Logger.getLogger(PdfWorker::class.java.name).also { it.addHandler(handler) }
        try {
            val plain =
                PdfWorker(
                    executor,
                    fx,
                    open = { file, password ->
                        PdfDocument
                            .open(
                                file,
                                password,
                            ).also { if (it is PdfOpenResult.Opened) documents += it.document }
                    },
                )
            plain.reopen(TestPdfs.text, null, { it.pageCount }) {}
            plain.submit<Unit>(WorkPriority.VISIBLE, { throw IOException("broken page") }, {})
            executor.runAll()
            fx.runAll()
            assertEquals(listOf("broken page"), records.map { it.thrown?.message })
            assertEquals(Level.WARNING, records.single().level)
            plain.dispose()
            executor.runAll()
            assertTrue(documents.single().closed)
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test
    fun `jobs run on the opened document and deliver their result on the FX runner`() {
        openText()
        val results = mutableListOf<Int>()
        worker.submit(WorkPriority.VISIBLE, { it.pageCount }, { results += it })
        executor.runAll()
        assertEquals(emptyList(), results)
        fx.runAll()
        assertEquals(listOf(1), results)
    }

    @Test
    fun `jobs queued for a document that did not open never run`() {
        worker.reopen(TestPdfs.notPdf, null, { it.pageCount }) {}
        executor.runAll()
        val ran = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        worker.submit(WorkPriority.VISIBLE, { ran += "job" }, {}, { errors += it })
        executor.runAll()
        fx.runAll()
        assertEquals(emptyList(), ran)
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `a reopen drops the jobs queued before it and the results not yet delivered`() {
        openText()
        val ran = mutableListOf<String>()
        val delivered = mutableListOf<Int>()
        worker.submit(WorkPriority.VISIBLE, { it.pageCount }, { delivered += it })
        executor.runAll()
        record(ran, "queued", WorkPriority.VISIBLE)
        worker.reopen(TestPdfs.text, null, { it.pageCount }) {}
        record(ran, "after", WorkPriority.VISIBLE)
        executor.runAll()
        fx.runAll()
        assertEquals(listOf("after"), ran)
        assertEquals(emptyList(), delivered)
    }

    @Test
    fun `cancelQueued drops the queued jobs it matches and keeps the others`() {
        openText()
        val ran = mutableListOf<String>()
        record(ran, "page 1", WorkPriority.VISIBLE, tag = 1)
        record(ran, "page 2", WorkPriority.VISIBLE, tag = 2)
        record(ran, "search", WorkPriority.SEARCH, tag = 1)
        worker.cancelQueued { it.priority == WorkPriority.VISIBLE && it.tag == 1 }
        executor.runAll()
        assertEquals(listOf("page 2", "search"), ran)
    }

    @Test
    fun `a failing job hands its error to onError on the FX runner, out of memory too`() {
        openText()
        val errors = mutableListOf<Throwable>()
        val results = mutableListOf<Unit>()
        val broken = IOException("Could not read page 1")
        val memory = OutOfMemoryError("Java heap space")
        worker.submit<Unit>(WorkPriority.VISIBLE, { throw broken }, { results.add(it) }, { errors.add(it) })
        worker.submit<Unit>(WorkPriority.VISIBLE, { throw memory }, { results.add(it) }, { errors.add(it) })
        executor.runAll()
        assertEquals(emptyList(), errors)
        fx.runAll()
        assertEquals(listOf<Throwable>(broken, memory), errors)
        assertEquals(emptyList(), results)
    }

    @Test
    fun `a failing job reported after a reopen is dropped`() {
        openText()
        val errors = mutableListOf<Throwable>()
        worker.submit(WorkPriority.VISIBLE, { throw IOException("late") }, {}, { errors += it })
        executor.runAll()
        worker.reopen(TestPdfs.text, null, { it.pageCount }) {}
        fx.runAll()
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `dispose drops late results, refuses new jobs, closes the document, shuts down without interrupting`() {
        openText()
        val delivered = mutableListOf<Int>()
        worker.submit(WorkPriority.VISIBLE, { it.pageCount }, { delivered += it })
        executor.runAll()
        worker.dispose()
        worker.dispose()
        val ran = mutableListOf<String>()
        record(ran, "after", WorkPriority.VISIBLE)
        executor.runAll()
        fx.runAll()
        assertEquals(emptyList(), delivered)
        assertEquals(emptyList(), ran)
        assertEquals(opened, closed)
        assertTrue(worker.disposed)
        assertTrue(executor.isShutdown)
        assertFalse(executor.shutDownNow)
    }

    @Test
    fun `a reopen after dispose opens nothing`() {
        worker.dispose()
        executor.runAll()
        worker.reopen(TestPdfs.text, null, { it.pageCount }) {}
        executor.runAll()
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `the document is opened, used and closed on the worker's own thread`() {
        val threads = mutableListOf<String>()
        val closedLatch = CountDownLatch(1)
        val threaded =
            PdfWorker(
                NamedThreads.single("sluide-pdf-test"),
                runOnFx = Runnable::run,
                open = { file, password ->
                    synchronized(threads) { threads += "open " + Thread.currentThread().name }
                    PdfDocument.open(file, password)
                },
                close = { document ->
                    synchronized(threads) { threads += "close " + Thread.currentThread().name }
                    document.close()
                    closedLatch.countDown()
                },
            )
        val used = CountDownLatch(1)
        threaded.reopen(TestPdfs.text, null, { it.pageCount }) {}
        threaded.submit(
            WorkPriority.VISIBLE,
            {
                synchronized(threads) { threads += "work " + Thread.currentThread().name }
                it.pageCount
            },
            { used.countDown() },
        )
        assertTrue(used.await(30, TimeUnit.SECONDS))
        threaded.dispose()
        assertTrue(closedLatch.await(30, TimeUnit.SECONDS))
        assertEquals(
            listOf("open sluide-pdf-test", "work sluide-pdf-test", "close sluide-pdf-test"),
            synchronized(threads) { threads.toList() },
        )
    }
}
