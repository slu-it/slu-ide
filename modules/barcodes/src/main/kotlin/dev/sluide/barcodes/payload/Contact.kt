package dev.sluide.barcodes.payload

import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import com.google.zxing.client.result.AddressBookParsedResult
import com.google.zxing.client.result.ResultParser

/**
 * A contact card that the Contact template writes as a vCard and that vCard and MECARD texts are read into.
 * A literal backslash before `n` in a value comes back as a line break (ZXing's order of unescaping).
 */
internal data class Contact(
    val name: String,
    val organization: String,
    val phone: String,
    val email: String,
    val website: String,
) {
    /** A vCard 3.0 with CRLF line ends; empty fields are left out and values escaped per RFC 2426. */
    fun text(): String {
        val lines = mutableListOf("BEGIN:VCARD", "VERSION:3.0")
        if (name.isNotBlank()) {
            val trimmed = name.trim()
            val split = trimmed.lastIndexOf(' ')
            val structured =
                if (split < 0) {
                    "${escape(trimmed)};;;;"
                } else {
                    "${escape(trimmed.substring(split + 1))};${escape(trimmed.substring(0, split).trim())};;;"
                }
            lines += "N:$structured"
            lines += "FN:${escape(trimmed)}"
        }

        fun add(
            key: String,
            value: String,
        ) {
            if (value.isNotBlank()) lines += "$key:${escape(value.trim())}"
        }
        add("ORG", organization)
        add("TEL", phone)
        add("EMAIL", email)
        add("URL", website)
        lines += "END:VCARD"
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    companion object {
        private fun escape(value: String) =
            value
                .replace("\\", "\\\\")
                .replace(",", "\\,")
                .replace(";", "\\;")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n")

        /** Reads a vCard (2.1, 3.0, 4.0) or MECARD text; null when it is neither. */
        fun parse(text: String): Contact? {
            val parsed =
                try {
                    ResultParser.parseResult(
                        Result(text, null, null, BarcodeFormat.QR_CODE),
                    ) as? AddressBookParsedResult
                } catch (_: RuntimeException) {
                    null
                } ?: return null
            return Contact(
                name = parsed.names?.firstOrNull().orEmpty(),
                organization = parsed.org.orEmpty(),
                phone = parsed.phoneNumbers?.firstOrNull().orEmpty(),
                email = parsed.emails?.firstOrNull().orEmpty(),
                website = parsed.urLs?.firstOrNull().orEmpty(),
            )
        }
    }
}
