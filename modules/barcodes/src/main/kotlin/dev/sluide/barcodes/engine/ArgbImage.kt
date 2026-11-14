package dev.sluide.barcodes.engine

/** An image as ARGB pixels, row by row from the top left. */
internal class ArgbImage(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    init {
        require(
            width >= 0 && height >= 0 && argb.size == width * height,
        ) { "Image $width×$height has ${argb.size} pixels" }
    }
}
