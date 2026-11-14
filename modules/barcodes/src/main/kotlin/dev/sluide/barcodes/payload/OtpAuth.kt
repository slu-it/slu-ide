package dev.sluide.barcodes.payload

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8

/** Reads `otpauth://totp|hotp/<label>?secret=..` links (ZXing does not know them). */
internal object OtpAuth {
    private const val PREFIX = "otpauth://"

    fun parse(text: String): Payload? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
        val rest = trimmed.substring(PREFIX.length)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val type = rest.substring(0, slash).lowercase()
        if (type != "totp" && type != "hotp") return null
        val afterType = rest.substring(slash + 1)
        val queryStart = afterType.indexOf('?')
        val rawLabel = if (queryStart < 0) afterType else afterType.substring(0, queryStart)
        val params = if (queryStart < 0) emptyMap() else query(afterType.substring(queryStart + 1))
        val secret = params["secret"]?.takeIf { it.isNotBlank() } ?: return null
        val label = decode(rawLabel.replace("+", "%2B"))
        val colon = label.indexOf(':')
        val labelIssuer = if (colon >= 0) label.substring(0, colon).trim() else ""
        val account = (if (colon >= 0) label.substring(colon + 1) else label).trim()
        val issuer = params["issuer"].orEmpty().ifBlank { labelIssuer }
        val fields =
            listOf(
                PayloadField("Type", type.uppercase()),
                PayloadField("Issuer", issuer),
                PayloadField("Account", account),
                PayloadField("Secret", secret, secret = true),
                PayloadField("Algorithm", params["algorithm"].orEmpty()),
                PayloadField("Digits", params["digits"].orEmpty()),
                PayloadField("Period", if (type == "totp") params["period"].orEmpty() else ""),
                PayloadField("Counter", if (type == "hotp") params["counter"].orEmpty() else ""),
            )
        return Payload("One-time password", fields.filter { it.value.isNotEmpty() })
    }

    private fun query(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in text.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            result.putIfAbsent(decode(pair.substring(0, eq)).lowercase(), decode(pair.substring(eq + 1)).trim())
        }
        return result
    }

    private fun decode(value: String) =
        try {
            URLDecoder.decode(value, UTF_8)
        } catch (_: IllegalArgumentException) {
            value
        }
}
