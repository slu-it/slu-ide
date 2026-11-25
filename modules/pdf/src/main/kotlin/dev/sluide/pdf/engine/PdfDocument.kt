package dev.sluide.pdf.engine

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.io.RandomAccessRead
import org.apache.pdfbox.io.RandomAccessReadBufferedFile
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.IdentityHashMap

/**
 * An open PDF (ADR 0192), read lazily from its file.
 *
 * **Confined to one thread:** open, use and close it on the same thread. PDFBox documents are not thread-safe; two
 * threads on one document gave silently wrong pages in the spike. A view that renders and extracts text at once opens
 * two documents. No method relies on `Thread.interrupt`, and the thread must not be interrupted while it works: an
 * interrupt closes the file channel and ruins the document. A render is stopped through its `cancelled` check instead.
 *
 * The pages are those of the page tree, in its order, read once when the document opens. Before that the page tree
 * is made consistent in memory (`repairPageTree`): its `/Count` values are set to the real numbers and looping or
 * broken kids are removed, so that page i is the same page for its size, text, links and render. The page methods throw only `IOException` for a page that cannot be read, runtime failures of PDFBox
 * included, so a view catches one type to show "Could not show page N.".
 */
class PdfDocument private constructor(
    private val document: PDDocument,
) : Closeable {
    private val pages = document.pages.also { repairPageTree(it.cosObject) }.toList()
    private val geometries = pages.map(::PageGeometry)

    val pageCount: Int get() = geometries.size

    /** The size of each page as displayed, in points. */
    val pageSizes: List<PageSize> = geometries.map(PageGeometry::displayedSize)

    private val navigation =
        DocumentNavigation(
            document,
            pages,
            geometries,
            IdentityHashMap<COSDictionary, Int>().also { index ->
                pages.forEachIndexed { number, page -> index.putIfAbsent(page.cosObject, number) }
            },
        )

    /** The outline (bookmarks) of the document; empty if it has none. Items whose destination is unknown have no target. */
    fun outline(): List<OutlineItem> = navigation.outline()

    /**
     * The links of page [page] that lead somewhere: to a place in the document, or to a web page with `http`, `https`
     * or `mailto`; other schemes (`javascript:`, `file:`), links without a target and links without a rectangle are
     * left out.
     */
    fun links(page: Int): List<PageLink> = onPage(page) { navigation.links(page) }

    private val rendering = PageRendering(document)

    /**
     * Renders page [page] (0-based) at [scale] (1 = 72 dpi), or at the largest scale within [MAX_PIXELS]; a side of
     * 0 points is 1 pixel. Returns `null` once [cancelled] says so; it is asked before each drawing step. The document
     * stays usable after a cancelled render. A page PDFBox cannot draw, or one that does not exist, throws an
     * `IOException`; PDFBox draws a page whose content is broken as far as it can, often blank.
     */
    fun render(
        page: Int,
        scale: Double,
        cancelled: () -> Boolean = { false },
    ): RenderedPage? = onPage(page) { rendering.render(page, pageSizes[page], scale, cancelled) }

    /** The text of page [page], as [pageText] gives it, without building its boxes. */
    fun plainText(page: Int): String =
        onPage(page) { PageTextExtraction(geometries[page], withBoxes = false).extract(document, page).text }

    /**
     * The text of page [page] in reading order (sorted by position, lines ended by `\n`, the page too) with the box of
     * each char on the page as displayed. Text outside the crop box is left out. A PDF that restricts copying gives its
     * text anyway. A page without a content stream has the text "".
     */
    fun pageText(page: Int): PageText =
        onPage(page) { PageTextExtraction(geometries[page], withBoxes = true).extract(document, page) }

    /** [work] on page [page], its runtime failures (PDFBox's and a missing page) turned into an `IOException`. */
    private fun <T> onPage(
        page: Int,
        work: () -> T,
    ): T =
        try {
            work()
        } catch (e: RuntimeException) {
            throw IOException("Could not read page ${page + 1}: ${e.message}", e)
        }

    override fun close() = document.close()

    /** Whether [close] closed the PDFBox document. */
    internal val closed: Boolean get() = document.document.isClosed

    companion object {
        /** The most pixels a [render] gives: 16 million, 64 MB as ARGB. */
        const val MAX_PIXELS = 16_000_000L

        private const val MAX_REASON_LENGTH = 200
        private const val CANNOT_READ = "cannot read the file"

        /** Opens [file] with the user [password], if it has one. Reads the file, so call it off the FX thread. */
        fun open(
            file: Path,
            password: String? = null,
        ): PdfOpenResult = open(file, password, load = ::load)

        /**
         * Loads from the file, not into a byte array: PDFBox reads the parts of the file it needs when it needs them,
         * and the document closes the file. PDFBox closes it itself only when parsing fails with an `IOException`, so
         * any failure closes it here. PDFBox's `StandardSecurityHandler` takes a `null` password as the empty one, as
         * `Loader.loadPDF(File)` does.
         */
        internal fun load(
            file: File,
            password: String?,
            parse: (RandomAccessRead, String?) -> PDDocument = { read, given -> Loader.loadPDF(read, given) },
        ): PDDocument {
            val read = RandomAccessReadBufferedFile(file)
            return try {
                parse(read, password)
            } catch (e: Throwable) {
                read.close()
                throw e
            }
        }

        internal fun open(
            file: Path,
            password: String?,
            create: (PDDocument) -> PdfDocument = ::PdfDocument,
            load: (File, String?) -> PDDocument,
        ): PdfOpenResult =
            try {
                val document = load(file.toFile(), password)
                val created =
                    try {
                        create(document)
                    } catch (e: Throwable) {
                        document.close()
                        throw e
                    }
                if (created.pageCount == 0) {
                    created.close()
                    PdfOpenResult.NoPages
                } else {
                    PdfOpenResult.Opened(created)
                }
            } catch (_: InvalidPasswordException) {
                if (password == null) PdfOpenResult.NeedsPassword else PdfOpenResult.WrongPassword
            } catch (_: NoSuchFileException) {
                PdfOpenResult.Invalid("file not found")
            } catch (_: AccessDeniedException) {
                PdfOpenResult.Invalid(CANNOT_READ)
            } catch (e: FileSystemException) {
                // Its message starts with the path; the reason alone is what the user needs.
                PdfOpenResult.Invalid(e.reason ?: CANNOT_READ)
            } catch (e: IOException) {
                PdfOpenResult.Invalid(reason(e))
            } catch (e: RuntimeException) {
                PdfOpenResult.Invalid(reason(e))
            } catch (_: OutOfMemoryError) {
                PdfOpenResult.TooLarge
            }

        private fun reason(error: Exception): String {
            val message = error.message ?: return error.javaClass.simpleName
            return message.lineSequence().first().take(MAX_REASON_LENGTH)
        }
    }
}
