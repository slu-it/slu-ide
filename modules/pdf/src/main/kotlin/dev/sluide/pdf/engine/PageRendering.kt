package dev.sluide.pdf.engine

import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.PageDrawer
import org.apache.pdfbox.rendering.PageDrawerParameters
import java.awt.image.DataBufferInt
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Renders pages of one document and stops a render between two content-stream operators once its `cancelled` check
 * says so: a `PageDrawer` that checks before each operator. Interrupting the thread is no way to stop it (it would
 * close the document's file channel). Each render has a `PDFRenderer` of its own (creating one costs well under a
 * microsecond), so no renderer keeps the last page image alive.
 */
internal class PageRendering(
    private val document: PDDocument,
) {
    private class CancellableRenderer(
        document: PDDocument,
        private val cancelled: () -> Boolean,
    ) : PDFRenderer(document) {
        override fun createPageDrawer(parameters: PageDrawerParameters): PageDrawer =
            object : PageDrawer(parameters) {
                override fun processOperator(
                    operator: Operator,
                    operands: List<COSBase>,
                ) {
                    if (cancelled()) throw RenderCancelled()
                    super.processOperator(operator, operands)
                }
            }
    }

    /** Thrown out of PDFBox's drawing loop; it lets runtime exceptions through, so the drawer is simply dropped. */
    private class RenderCancelled : RuntimeException(null, null, false, false)

    fun render(
        page: Int,
        size: PageSize,
        scale: Double,
        cancelled: () -> Boolean,
    ): RenderedPage? {
        val used = cappedScale(size, scale)
        val image =
            try {
                CancellableRenderer(document, cancelled).renderImage(page, used.toFloat(), ImageType.RGB)
            } catch (_: RenderCancelled) {
                return null
            }
        // A page without content runs no operator, so the check is made once more at the end.
        if (cancelled()) return null
        // An RGB image keeps its pixels as 0x00RRGGBB in one int array of its own; the alpha byte is set in place.
        val pixels = (image.raster.dataBuffer as DataBufferInt).data
        for (index in pixels.indices) pixels[index] = pixels[index] or OPAQUE
        return RenderedPage(image.width, image.height, pixels, used)
    }

    companion object {
        private const val OPAQUE = 0xFF000000.toInt()

        /**
         * [scale], or the largest scale at which a page of [size] has at most [PdfDocument.MAX_PIXELS] pixels, counted
         * as `PDFRenderer` sizes its image: each side rounded down, but at least 1 pixel. So the long side alone stays
         * within the cap too (a thin page), and a side of 0 points is 1 pixel at any scale; a page of 0 by 0 points
         * is 1 pixel and keeps the asked scale.
         */
        fun cappedScale(
            size: PageSize,
            scale: Double,
        ): Double {
            fun pixels(at: Double) = max(floor(size.width * at), 1.0) * max(floor(size.height * at), 1.0)
            if (pixels(scale) <= PdfDocument.MAX_PIXELS) return scale
            val area = sqrt(PdfDocument.MAX_PIXELS / (size.width * size.height))
            return min(area, PdfDocument.MAX_PIXELS / max(size.width, size.height))
        }
    }
}
