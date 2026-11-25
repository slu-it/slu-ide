package dev.sluide.pdf.view

import dev.sluide.pdf.engine.PdfDocument
import dev.sluide.pdf.engine.PdfOpenResult
import javafx.application.Platform
import java.nio.file.Path
import java.util.PriorityQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

private val log = System.getLogger(PdfWorker::class.java.name)

/** What a job of a [PdfWorker] is for: queued jobs run in this order, first come first served within one. */
internal enum class WorkPriority { VISIBLE, NEAR, THUMBNAIL, TEXT, SEARCH }

/** A job waiting in a [PdfWorker]'s queue, as [PdfWorker.cancelQueued] sees it; [tag] is what its submitter gave. */
internal class PdfJob(
    val priority: WorkPriority,
    val tag: Any?,
)

/** What [PdfWorker.reopen] found: the value read from the opened document, or why it did not open. */
internal sealed interface Opening<out T> {
    data class Opened<T>(
        val value: T,
    ) : Opening<T>

    /** Any [PdfOpenResult] but `Opened`. */
    data class Failed(
        val result: PdfOpenResult,
    ) : Opening<Nothing>
}

/**
 * The thread of one [PdfDocument] (ADR 0192): opens, uses and closes it on [executor], which must have one thread, so
 * that the document is never touched from another. Queued jobs run by [WorkPriority], first come first served within
 * one; opening and closing go before them. Results reach the FX thread through [runOnFx], unless a [reopen] or
 * [dispose] came in between (a generation count): late results are dropped, and so are jobs queued before. Nothing
 * interrupts the thread, which would ruin the document's file channel; [dispose] shuts the executor down and closes the
 * document on it. Call everything but the jobs themselves on the FX thread.
 */
internal class PdfWorker(
    private val executor: ExecutorService,
    private val runOnFx: (Runnable) -> Unit = Platform::runLater,
    private val open: (Path, String?) -> PdfOpenResult = { file, password -> PdfDocument.open(file, password) },
    private val close: (PdfDocument) -> Unit = PdfDocument::close,
) {
    private class Entry(
        val rank: Int,
        val sequence: Long,
        val job: PdfJob?,
        val run: () -> Unit,
    )

    private val queue = PriorityQueue(compareBy<Entry>({ it.rank }, { it.sequence }))
    private var sequence = 0L
    private val generation = AtomicInteger()

    /** The document of the latest open; only the worker's thread touches it. */
    private var document: PdfDocument? = null

    /** Whether [dispose] was called; the worker then takes no more work. */
    var disposed = false
        private set

    /**
     * Queues [work] on the document; its result goes to [onFx], or what it threw (an `OutOfMemoryError` too) to
     * [onError], both on the FX thread and only while no [reopen] or [dispose] came in between. A job that finds no
     * document open (it did not open, or was closed) does not run and reports that to [onError]; one queued after
     * [dispose] never runs. [tag] identifies the job for [cancelQueued].
     */
    fun <T> submit(
        priority: WorkPriority,
        work: (PdfDocument) -> T,
        onFx: (T) -> Unit,
        onError: (Throwable) -> Unit = ::logFailure,
        tag: Any? = null,
    ) {
        if (disposed) return
        val current = generation.get()
        enqueue(priority.ordinal, PdfJob(priority, tag)) {
            val opened = document
            if (opened == null) {
                deliver(current) { onError(IllegalStateException(NO_DOCUMENT)) }
                return@enqueue
            }
            val result =
                try {
                    work(opened)
                } catch (e: Throwable) {
                    deliver(current) { onError(e) }
                    return@enqueue
                }
            deliver(current) { onFx(result) }
        }
    }

    /** Drops the queued jobs [predicate] matches; one already running goes on (a render has its own cancel check). */
    fun cancelQueued(predicate: (PdfJob) -> Boolean) {
        synchronized(queue) { queue.removeIf { entry -> entry.job != null && predicate(entry.job) } }
    }

    /**
     * Closes the document, if any, and opens [file] with [password] in its place; [read] runs on the opened document,
     * and its value (or why the file did not open) goes to [onFx]. Jobs queued before and their late results are
     * dropped. An open that throws (a stack overflow in a deeply nested file) counts as an invalid file; a [read] that
     * throws closes the document again and counts as an invalid file, or as one too large when out of memory.
     */
    fun <T> reopen(
        file: Path,
        password: String?,
        read: (PdfDocument) -> T,
        onFx: (Opening<T>) -> Unit,
    ) {
        if (disposed) return
        val current = generation.incrementAndGet()
        synchronized(queue) { queue.clear() }
        enqueue(CONTROL, job = null) {
            closeDocument()
            val opening =
                try {
                    when (val result = open(file, password)) {
                        is PdfOpenResult.Opened -> readOpened(result.document, read)
                        else -> Opening.Failed(result)
                    }
                } catch (e: Throwable) {
                    Opening.Failed(PdfOpenResult.Invalid(reason(e)))
                }
            deliver(current) { onFx(opening) }
        }
    }

    private fun <T> readOpened(
        opened: PdfDocument,
        read: (PdfDocument) -> T,
    ): Opening<T> =
        try {
            Opening.Opened(read(opened)).also { document = opened }
        } catch (e: Throwable) {
            closeSafely(opened)
            Opening.Failed(if (e is OutOfMemoryError) PdfOpenResult.TooLarge else PdfOpenResult.Invalid(reason(e)))
        }

    /**
     * Drops the queued jobs and late results and closes the document on the worker's thread (its file could no longer
     * be opened); jobs queued later find no document until a [reopen].
     */
    fun close() {
        if (disposed) return
        generation.incrementAndGet()
        synchronized(queue) { queue.clear() }
        enqueue(CONTROL, job = null) { closeDocument() }
    }

    /** Drops the queued jobs and late results, closes the document on the worker's thread and shuts the executor down. */
    fun dispose() {
        if (disposed) return
        disposed = true
        generation.incrementAndGet()
        // Jobs still queued find no document once it is closed, which goes first.
        enqueue(CONTROL, job = null) { closeDocument() }
        executor.shutdown()
    }

    private fun closeDocument() {
        val opened = document ?: return
        document = null
        closeSafely(opened)
    }

    /** Closes [opened]; a failure is logged, so that the next open still runs. */
    private fun closeSafely(opened: PdfDocument) {
        try {
            close(opened)
        } catch (e: Throwable) {
            log.log(System.Logger.Level.WARNING, "Closing a PDF failed", e)
        }
    }

    private fun enqueue(
        rank: Int,
        job: PdfJob?,
        run: () -> Unit,
    ) {
        synchronized(queue) { queue += Entry(rank, sequence++, job, run) }
        executor.execute(::runNext)
    }

    /** Runs the first job of the queue, if any is left: each [enqueue] gives the executor one call of this. */
    private fun runNext() {
        val entry = synchronized(queue) { queue.poll() } ?: return
        entry.run()
    }

    private fun deliver(
        generationOfJob: Int,
        action: () -> Unit,
    ) = runOnFx(Runnable { if (generationOfJob == generation.get()) action() })

    private fun logFailure(error: Throwable) = log.log(System.Logger.Level.WARNING, "PDF work failed", error)

    private companion object {
        /** The rank of opening and closing: before every job. */
        const val CONTROL = -1

        /** What a job that finds no document open reports. */
        const val NO_DOCUMENT = "No PDF document is open"

        fun reason(error: Throwable) = error.message ?: error.javaClass.simpleName
    }
}
