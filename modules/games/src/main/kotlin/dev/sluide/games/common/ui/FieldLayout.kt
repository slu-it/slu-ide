package dev.sluide.games.common.ui

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Fits a [fieldWidth] x [fieldHeight] field into an area keeping its aspect, centred, at least scale 1, then times the
 * zoom of [fit] (below 1 the field is smaller than the area). Pure.
 */
internal class FieldLayout(
    val fieldWidth: Int,
    val fieldHeight: Int,
) {
    fun fit(
        areaWidth: Double,
        areaHeight: Double,
        outputScale: Double,
        zoom: Double = 1.0,
    ): FieldPlacement {
        val scale = max(1.0, min(areaWidth / fieldWidth, areaHeight / fieldHeight)) * zoom
        return FieldPlacement(
            scale,
            (areaWidth - fieldWidth * scale) / 2,
            (areaHeight - fieldHeight * scale) / 2,
            outputScale,
            fieldWidth,
            fieldHeight,
        )
    }
}

/**
 * Where the field lies in the view: [scale] view units per field pixel, the field's top left at the offsets, on a
 * screen with [outputScale] device pixels per view unit. [fieldWidth] and [fieldHeight] give [width] and [height].
 */
internal data class FieldPlacement(
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double,
    val outputScale: Double,
    val fieldWidth: Int,
    val fieldHeight: Int,
) {
    /** View coordinate of field coordinate [fieldX], snapped to device pixels. */
    fun x(fieldX: Double): Double = snap(offsetX, fieldX)

    fun y(fieldY: Double): Double = snap(offsetY, fieldY)

    /** The snapped field width in view coordinates. */
    val width: Double get() = x(fieldWidth.toDouble()) - x(0.0)

    val height: Double get() = y(fieldHeight.toDouble()) - y(0.0)

    /** The snapped width of the field span [fw] at [fx], at least one device pixel so that thin shapes never vanish. */
    fun spanWidth(
        fx: Double,
        fw: Double,
    ): Double = max(x(fx + fw) - x(fx), 1 / outputScale)

    /** Like [spanWidth] for the height. */
    fun spanHeight(
        fy: Double,
        fh: Double,
    ): Double = max(y(fy + fh) - y(fy), 1 / outputScale)

    // (round(offset * os) + floor(v * scale * os)) / os
    private fun snap(
        offset: Double,
        v: Double,
    ): Double = (round(offset * outputScale) + floor(v * scale * outputScale)) / outputScale
}
