package dev.sluide.pdf.engine

import org.apache.pdfbox.pdmodel.PDPage
import kotlin.math.abs

/**
 * Maps positions of a page into the page as displayed (top-left origin, crop box and rotation applied), as
 * `PDFRenderer` draws it. Two frames lead there: PDF user space (bottom-left origin, used by annotations and
 * destinations) and the unrotated frame `U` (top-left origin, relative to the crop box, used by text positions).
 */
internal class PageGeometry(
    page: PDPage,
) {
    private val cropBox = page.cropBox
    private val left = cropBox.lowerLeftX.toDouble()
    private val bottom = cropBox.lowerLeftY.toDouble()

    /** Width and height of the crop box, unrotated. */
    val width = cropBox.width.toDouble()
    val height = cropBox.height.toDouble()

    /** The page rotation in degrees: 0, 90, 180 or 270 (PDFBox normalises `/Rotate`). */
    val rotation = page.rotation

    val displayedSize: PageSize = if (rotation % 180 == 0) PageSize(width, height) else PageSize(height, width)

    /** A point of the unrotated frame `U` in the displayed page. */
    fun displayed(
        x: Double,
        y: Double,
    ): Pair<Double, Double> =
        when (rotation) {
            90 -> (height - y) to x
            180 -> (width - x) to (height - y)
            270 -> y to (width - x)
            else -> x to y
        }

    /** A point of PDF user space in the frame `U`. */
    fun unrotated(
        userX: Double,
        userY: Double,
    ): Pair<Double, Double> = (userX - left) to (height - (userY - bottom))

    /** The displayed bounds of a rectangle of the frame `U`. */
    fun displayedRect(
        x: Double,
        y: Double,
        rectWidth: Double,
        rectHeight: Double,
    ): PageRect {
        val (x1, y1) = displayed(x, y)
        val (x2, y2) = displayed(x + rectWidth, y + rectHeight)
        return PageRect(minOf(x1, x2), minOf(y1, y2), abs(x2 - x1), abs(y2 - y1))
    }

    /** The displayed bounds of a rectangle of PDF user space given by two corners. */
    fun displayedUserRect(
        userX1: Double,
        userY1: Double,
        userX2: Double,
        userY2: Double,
    ): PageRect {
        val (x1, y1) = unrotated(userX1, userY1)
        val (x2, y2) = unrotated(userX2, userY2)
        return displayedRect(minOf(x1, x2), minOf(y1, y2), abs(x2 - x1), abs(y2 - y1))
    }
}
