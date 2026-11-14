package dev.sluide.barcodes.engine

/**
 * The modules of a code, one entry per module, true for a dark one, without the quiet zone. Linear codes have
 * height 1: one entry per bar module.
 */
internal class ModuleGrid(
    val width: Int,
    val height: Int,
    private val bits: BooleanArray,
) {
    init {
        require(width > 0 && height > 0 && bits.size == width * height) { "Grid $width×$height has ${bits.size} bits" }
    }

    operator fun get(
        x: Int,
        y: Int,
    ): Boolean = bits[y * width + x]
}

/** What [Generator.generate] made of a text: a [Code], or a [Failure] that says why not. */
internal sealed interface Generated {
    /** A generated code; [summary] is the one line the UI shows under it, e.g. "QR version 3 · 29×29 · ECC M". */
    data class Code(
        val grid: ModuleGrid,
        val format: CodeFormat,
        val summary: String,
    ) : Generated

    /** Why the text cannot become the code; [charIndex] is the 0-based index of the offending character, if one. */
    data class Failure(
        val message: String,
        val charIndex: Int? = null,
    ) : Generated
}
