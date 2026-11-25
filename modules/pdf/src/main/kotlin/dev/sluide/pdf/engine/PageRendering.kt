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
import kotlin.math.sqrt

/**
 * Renders pages of one document and stops a render between two content-stream operators once its `cancelled` check
 * says so: a `PageDrawer` that checks before each operator. Interrupting the thread is no way to stop it (it would
 * close the document's file channel).
 */
internal class PageRendering(
    document: PDDocument,
) {
    /** The check of the render in progress; set by [render] before PDFBox draws. */
    private lateinit var cancelled: () -> Boolean

    private val renderer =
        object : PDFRenderer(document) {
            override fun createPageDrawer(parameters: PageDrawerParameters): PageDrawer =
                CancellablePageDrawer(parameters)
        }

    private inner class CancellablePageDrawer(
        parameters: PageDrawerParameters,
    ) : PageDrawer(parameters) {
        override fun processOperator(
            operator: Operator,
            operands: List<COSBase>,
        ) {
            if (cancelled()) throw RenderCancelled()
            super.processOperator(operator, operands)
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
        this.cancelled = cancelled
        val image =
            try {
                renderer.renderImage(page, used.toFloat(), ImageType.RGB)
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
         * as `PDFRenderer` sizes its image (each side rounded down, at least 1).
         */
        fun cappedScale(
            size: PageSize,
            scale: Double,
        ): Double {
            fun pixels(at: Double) = max(floor(size.width * at), 1.0) * max(floor(size.height * at), 1.0)
            return if (pixels(scale) <= PdfDocument.MAX_PIXELS) {
                scale
            } else {
                sqrt(PdfDocument.MAX_PIXELS / (size.width * size.height))
            }
        }
    }
}
