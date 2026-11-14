package dev.sluide.barcodes.ui

import dev.sluide.barcodes.engine.Box
import javafx.scene.paint.Color
import kotlin.math.floor
import kotlin.math.roundToInt

/** Where content of [contentWidth] × [contentHeight] units goes on a canvas: [scale] logical pixels per unit, top left at ([x], [y]). */
internal data class Fit(
    val scale: Double,
    val x: Double,
    val y: Double,
    val contentWidth: Int,
    val contentHeight: Int,
) {
    val width get() = contentWidth * scale
    val height get() = contentHeight * scale
}

/**
 * The largest centred fit of [contentWidth] × [contentHeight] units into [availableWidth] × [availableHeight] logical
 * pixels, or null when nothing fits. With [wholePixels], the fit is worked out in device pixels (one logical pixel is
 * [renderScaleX] device pixels wide and [renderScaleY] high, as `Window.renderScaleX` says): a scale of 1 device pixel
 * per unit or more is rounded down to a whole number and the offsets to whole device pixels, so that module edges stay
 * crisp also at 125 % or 150 %; a smaller scale stays as it is. The scale follows the horizontal render scale; the
 * vertical offset is snapped with the vertical one. Without [wholePixels] (images) the scale is never above 1 logical
 * pixel per unit.
 */
internal fun fit(
    contentWidth: Int,
    contentHeight: Int,
    availableWidth: Double,
    availableHeight: Double,
    wholePixels: Boolean,
    renderScaleX: Double = 1.0,
    renderScaleY: Double = 1.0,
): Fit? {
    if (contentWidth <= 0 || contentHeight <= 0 || availableWidth < 1 || availableHeight < 1) return null
    val deviceWidth = availableWidth * renderScaleX
    val deviceHeight = availableHeight * renderScaleY
    var scale = minOf(deviceWidth / contentWidth, deviceHeight / contentHeight)
    val whole = wholePixels && scale >= 1
    if (whole) {
        scale = floor(scale)
    } else if (!wholePixels) {
        scale = minOf(scale, renderScaleX)
    }
    var x = (deviceWidth - contentWidth * scale) / 2
    var y = (deviceHeight - contentHeight * scale) / 2
    if (whole) {
        x = floor(x)
        y = floor(y)
    }
    return Fit(scale / renderScaleX, x / renderScaleX, y / renderScaleY, contentWidth, contentHeight)
}

/** A rectangle in canvas coordinates (logical pixels). */
internal data class Rect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    fun contains(
        pointX: Double,
        pointY: Double,
    ) = pointX >= x && pointX <= x + width && pointY >= y && pointY <= y + height
}

/** Where [box], in the pixels of the image, lies on the canvas when the image is placed as [fit] says. */
internal fun imageToCanvas(
    box: Box,
    fit: Fit,
): Rect = Rect(fit.x + box.x * fit.scale, fit.y + box.y * fit.scale, box.width * fit.scale, box.height * fit.scale)

/** The index of the smallest of [boxes] (image pixels) that contains the canvas point, so a code inside another wins; null if none. */
internal fun hitTest(
    x: Double,
    y: Double,
    boxes: List<Box>,
    fit: Fit,
): Int? =
    boxes.indices
        .filter { imageToCanvas(boxes[it], fit).contains(x, y) }
        .minByOrNull { boxes[it].width * boxes[it].height }

/** The ARGB int of [color], as the engine's options hold it. */
internal fun Color.toArgb(): Int =
    ((opacity * 255).roundToInt() shl 24) or
        ((red * 255).roundToInt() shl 16) or
        ((green * 255).roundToInt() shl 8) or
        (blue * 255).roundToInt()

/** The colour of the engine's ARGB int [argb]. */
internal fun colorOf(argb: Int): Color =
    Color.rgb((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, (argb ushr 24) / 255.0)
