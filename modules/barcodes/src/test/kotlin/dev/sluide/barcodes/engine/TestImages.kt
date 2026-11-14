package dev.sluide.barcodes.engine

internal const val WHITE = -1

/** The exported image of [text] as a [format] code. */
internal fun codeImage(
    text: String,
    format: CodeFormat,
    options: GenerateOptions = GenerateOptions(),
): ArgbImage {
    val code = Generator.generate(text, format, options) as Generated.Code
    return Export.argb(code, options)
}

/** Where the modules of [text] as a [format] code lie in its exported image: the image without the quiet zone. */
internal fun moduleArea(
    text: String,
    format: CodeFormat,
    options: GenerateOptions = GenerateOptions(),
): Box {
    val code = Generator.generate(text, format, options) as Generated.Code
    val layout = Layout.of(code, options)
    val left = (layout.quietX * layout.scale).toDouble()
    val top = (layout.quietY * layout.scale).toDouble()
    val width = (code.grid.width * layout.scale).toDouble()
    val height = if (format.is1D) layout.heightPx.toDouble() else (code.grid.height * layout.scale).toDouble()
    return Box(left, top, width, height)
}

/** A canvas of [width] × [height] in [background] with each image drawn at its top-left point. */
internal fun compose(
    width: Int,
    height: Int,
    vararg placed: Triple<ArgbImage, Int, Int>,
    background: Int = WHITE,
): ArgbImage {
    val pixels = IntArray(width * height) { background }
    for ((image, left, top) in placed) {
        for (y in 0 until image.height) {
            System.arraycopy(image.argb, y * image.width, pixels, (top + y) * width + left, image.width)
        }
    }
    return ArgbImage(width, height, pixels)
}

internal fun rotatedClockwise(image: ArgbImage): ArgbImage {
    val out = IntArray(image.argb.size)
    for (y in 0 until image.height) {
        for (x in 0 until image.width) out[x * image.height + (image.height - 1 - y)] = image.argb[y * image.width + x]
    }
    return ArgbImage(image.height, image.width, out)
}
