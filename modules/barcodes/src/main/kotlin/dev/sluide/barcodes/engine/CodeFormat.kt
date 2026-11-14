package dev.sluide.barcodes.engine

import com.google.zxing.BarcodeFormat

/** The barcode formats Thoreau knows; [generatable] ones can also be created, the others only read (ADR 0174). */
internal enum class CodeFormat(
    val title: String,
    val generatable: Boolean,
) {
    QR_CODE("QR Code", true),
    DATA_MATRIX("Data Matrix", true),
    AZTEC("Aztec", true),
    PDF_417("PDF417", true),
    CODE_128("Code 128", true),
    EAN_13("EAN-13", true),
    UPC_A("UPC-A", true),
    EAN_8("EAN-8", false),
    UPC_E("UPC-E", false),
    CODE_39("Code 39", false),
    CODE_93("Code 93", false),
    ITF("ITF", false),
    CODABAR("Codabar", false),
    ;

    /** True for the linear formats: one row of bars, drawn full height. */
    val is1D: Boolean
        get() = this in LINEAR

    /** The ZXing format of the same name. */
    internal val barcodeFormat: BarcodeFormat
        get() = BarcodeFormat.valueOf(name)

    internal companion object {
        private val LINEAR = setOf(CODE_128, EAN_13, UPC_A, EAN_8, UPC_E, CODE_39, CODE_93, ITF, CODABAR)

        /** The format of ZXing's [format], or null for one Thoreau does not offer (RSS, MaxiCode, extensions). */
        fun of(format: BarcodeFormat): CodeFormat? = entries.firstOrNull { it.name == format.name }
    }
}

/** QR error correction: L 7 %, M 15 %, Q 25 %, H 30 % of the code can be restored. */
internal enum class QrEcc { L, M, Q, H }

/**
 * How a code is generated and exported. [quietZone] is the margin in modules on each side, null for the format's
 * default ([quietZoneFor]); [foreground] and [background] are ARGB colours.
 */
internal data class GenerateOptions(
    val qrEcc: QrEcc = QrEcc.M,
    val aztecEccPercent: Int = 23,
    val pdf417Level: Int = 2,
    val sizePx: Int = 512,
    val quietZone: Int? = null,
    val foreground: Int = 0xFF000000.toInt(),
    val background: Int = -1,
) {
    /** The quiet zone in modules: [quietZone] if set, else QR 4, Data Matrix 1, Aztec 1, PDF417 2, linear codes 10. */
    fun quietZoneFor(format: CodeFormat): Int =
        quietZone ?: when {
            format.is1D -> 10
            format == CodeFormat.QR_CODE -> 4
            format == CodeFormat.PDF_417 -> 2
            else -> 1
        }
}
