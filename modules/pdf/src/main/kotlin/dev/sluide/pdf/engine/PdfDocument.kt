package dev.sluide.pdf.engine

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * An open PDF (ADR 0192), read lazily from its file.
 *
 * **Confined to one thread:** open, use and close it on the same thread. PDFBox documents are not thread-safe; two
 * threads on one document gave silently wrong pages in the spike. A view that renders and extracts text at once opens
 * two documents. No method relies on `Thread.interrupt`, and the thread must not be interrupted while it works: an
 * interrupt closes the file channel and ruins the document. A render is stopped through its `cancelled` check instead.
 */
class PdfDocument private constructor(
    private val document: PDDocument,
) : Closeable {
    private val geometries = List(document.numberOfPages) { PageGeometry(document.getPage(it)) }

    val pageCount: Int get() = geometries.size

    /** The size of each page as displayed, in points. */
    val pageSizes: List<PageSize> = geometries.map(PageGeometry::displayedSize)

    fun outline(): List<OutlineItem> = emptyList()

    fun links(page: Int): List<PageLink> = emptyList()

    private val rendering = PageRendering(document)

    /**
     * Renders page [page] (0-based) at [scale] (1 = 72 dpi), or at the largest scale within [MAX_PIXELS]. Returns
     * `null` once [cancelled] says so; it is asked before each drawing step. The document stays usable after a
     * cancelled render. A page PDFBox cannot draw throws its `IOException`.
     */
    fun render(
        page: Int,
        scale: Double,
        cancelled: () -> Boolean = { false },
    ): RenderedPage? = rendering.render(page, pageSizes[page], scale, cancelled)

    fun plainText(page: Int): String = ""

    fun pageText(page: Int): PageText = PageText("", emptyList())

    override fun close() = document.close()

    /** Whether [close] closed the PDFBox document. */
    internal val closed: Boolean get() = document.document.isClosed

    companion object {
        /** The most pixels a [render] gives: 16 million, 64 MB as ARGB. */
        const val MAX_PIXELS = 16_000_000L

        private const val MAX_REASON_LENGTH = 200

        /** Opens [file] with the user [password], if it has one. Reads the file, so call it off the FX thread. */
        fun open(
            file: Path,
            password: String? = null,
        ): PdfOpenResult = open(file, password, ::load)

        /**
         * Loads from the file, not into a byte array: PDFBox reads the parts of the file it needs when it needs them.
         * PDFBox's `StandardSecurityHandler` takes a `null` password as the empty one, as `Loader.loadPDF(File)` does.
         */
        internal fun load(
            file: File,
            password: String?,
        ): PDDocument = Loader.loadPDF(file, password)

        internal fun open(
            file: Path,
            password: String?,
            load: (File, String?) -> PDDocument,
        ): PdfOpenResult =
            try {
                val document = load(file.toFile(), password)
                if (document.numberOfPages == 0) {
                    document.close()
                    PdfOpenResult.NoPages
                } else {
                    PdfOpenResult.Opened(PdfDocument(document))
                }
            } catch (_: InvalidPasswordException) {
                if (password == null) PdfOpenResult.NeedsPassword else PdfOpenResult.WrongPassword
            } catch (_: NoSuchFileException) {
                PdfOpenResult.Invalid("The file does not exist.")
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
