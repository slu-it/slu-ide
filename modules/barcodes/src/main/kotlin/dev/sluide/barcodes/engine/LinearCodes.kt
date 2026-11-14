package dev.sluide.barcodes.engine

import com.google.zxing.oned.Code128Writer
import com.google.zxing.oned.EAN13Writer

/** The linear codes Thoreau generates: Code 128, EAN-13 and UPC-A, checked here so that errors name the character. */
internal object LinearCodes {
    fun generate(
        text: String,
        format: CodeFormat,
    ): Generated =
        when (format) {
            CodeFormat.CODE_128 -> code128(text)
            CodeFormat.EAN_13 -> ean(text, format, 12)
            CodeFormat.UPC_A -> ean(text, format, 11)
            else -> Generated.Failure("${format.title} can only be read, not generated")
        }

    /** Code 128 has no length limit of its own; beyond this many characters scanners give up long before. */
    const val CODE_128_MAX = 80

    private fun code128(text: String): Generated {
        if (text.length > CODE_128_MAX) {
            return Generated.Failure("Too long for Code 128: ${grouped(text.length)} of $CODE_128_MAX characters")
        }
        val bad = text.indexOfFirst { it.code > 127 }
        if (bad >= 0) {
            return Generated.Failure(
                "Code 128 takes ASCII only: ${describeChar(text, bad)} (character ${bad + 1})",
                bad,
            )
        }
        val modules = Code128Writer().encode(text)
        return bars(modules, CodeFormat.CODE_128, "Code 128 · ${modules.size} modules")
    }

    /** EAN-13 with [payload] 12 digits, or UPC-A with 11: the check digit is appended, or verified when given. */
    private fun ean(
        text: String,
        format: CodeFormat,
        payload: Int,
    ): Generated {
        val bad = text.indexOfFirst { it !in '0'..'9' }
        if (bad >= 0) {
            return Generated.Failure(
                "${format.title} takes digits only: ${describeChar(text, bad)} (character ${bad + 1})",
                bad,
            )
        }
        if (text.length != payload && text.length != payload + 1) {
            return Generated.Failure("${format.title} takes $payload or ${payload + 1} digits (has ${text.length})")
        }
        val expected = checkDigit(text.substring(0, payload))
        if (text.length == payload + 1 && text[payload] != expected) {
            return Generated.Failure("Wrong check digit ${text[payload]}; expected $expected", payload)
        }
        val number = text.substring(0, payload) + expected
        // UPC-A is the EAN-13 with a leading 0.
        val ean13 = if (format == CodeFormat.UPC_A) "0$number" else number
        return bars(EAN13Writer().encode(ean13), format, "${format.title} · $number")
    }

    /** The GS1 check digit of [digits]: weights 3 and 1 alternate from the rightmost digit, which has 3. */
    internal fun checkDigit(digits: String): Char {
        val sum = digits.reversed().withIndex().sumOf { (i, c) -> (c - '0') * if (i % 2 == 0) 3 else 1 }
        return '0' + (10 - sum % 10) % 10
    }

    private fun bars(
        modules: BooleanArray,
        format: CodeFormat,
        summary: String,
    ): Generated.Code =
        Generated.Code(
            gridOf(modules.size, 1) { x, _ -> modules[x] },
            format,
            summary,
        )
}
