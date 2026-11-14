package dev.sluide.barcodes.payload

/** The security types of a Wi-Fi QR code; [code] is what goes after `T:`. */
internal enum class WifiSecurity(
    val title: String,
    val code: String,
) {
    WPA("WPA/WPA2/WPA3", "WPA"),
    WEP("WEP", "WEP"),
    NONE("None", "nopass"),
}

/**
 * A Wi-Fi network as the `WIFI:` QR text describes it. An SSID that really starts and ends with double quotes may be
 * read without them by phones (Android treats surrounding quotes as delimiters).
 */
internal data class Wifi(
    val ssid: String,
    val security: WifiSecurity,
    val password: String,
    val hidden: Boolean,
) {
    /** The `WIFI:T:..;S:..;P:..;H:true;;` text; SSID and password are escaped, P and H are left out when unused. */
    fun text(): String =
        buildString {
            append("WIFI:T:")
                .append(security.code)
                .append(";S:")
                .append(escape(ssid))
                .append(';')
            if (security != WifiSecurity.NONE &&
                password.isNotEmpty()
            ) {
                append("P:").append(escape(password)).append(';')
            }
            if (hidden) append("H:true;")
            append(';')
        }

    companion object {
        private val ESCAPED = setOf('\\', ';', ',', ':', '"')

        private fun escape(value: String) =
            buildString {
                for (c in value) {
                    if (c in ESCAPED) append('\\')
                    append(c)
                }
            }

        /**
         * Reads a `WIFI:` text in any field order; null when it is not one, has no SSID or has a security type the
         * template cannot represent (such as `WPA2-EAP`).
         */
        fun parse(text: String): Wifi? {
            val reading = read(text) ?: return null
            val security = reading.security ?: return null
            return Wifi(reading.ssid, security, reading.password, reading.hidden)
        }

        /** Like [parse], but keeps an unknown security type as written; null when it is no `WIFI:` text or has no SSID. */
        fun read(text: String): WifiReading? {
            if (!text.startsWith("WIFI:")) return null
            val fields = mutableMapOf<String, String>()
            for (raw in splitFields(text.substring("WIFI:".length))) {
                val colon = raw.indexOf(':')
                if (colon <= 0) continue
                fields.putIfAbsent(raw.substring(0, colon).trim(), value(raw.substring(colon + 1)))
            }
            val ssid = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
            val code = fields["T"].orEmpty()
            val security =
                when (code.uppercase()) {
                    "WPA", "WPA2", "WPA3", "SAE" -> WifiSecurity.WPA
                    "WEP" -> WifiSecurity.WEP
                    "", "NOPASS" -> WifiSecurity.NONE
                    else -> null
                }
            return WifiReading(
                ssid,
                code,
                security,
                fields["P"].orEmpty(),
                fields["H"].equals("true", ignoreCase = true),
            )
        }

        /** The fields between unescaped semicolons, still escaped. */
        private fun splitFields(body: String): List<String> {
            val fields = mutableListOf<String>()
            val current = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    current.append(c).append(body[i + 1])
                    i += 2
                    continue
                }
                if (c == ';') {
                    fields += current.toString()
                    current.clear()
                } else {
                    current.append(c)
                }
                i++
            }
            if (current.isNotEmpty()) fields += current.toString()
            return fields
        }

        /** One pair of unescaped surrounding double quotes is dropped, then the escapes are removed. */
        private fun value(raw: String): String {
            var inner = raw
            if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"' &&
                trailingBackslashes(raw, raw.length - 1) % 2 == 0
            ) {
                inner = raw.substring(1, raw.length - 1)
            }
            return buildString {
                var i = 0
                while (i < inner.length) {
                    if (inner[i] == '\\' && i + 1 < inner.length) i++
                    append(inner[i])
                    i++
                }
            }
        }

        private fun trailingBackslashes(
            text: String,
            end: Int,
        ): Int {
            var n = 0
            while (end - 1 - n >= 0 && text[end - 1 - n] == '\\') n++
            return n
        }
    }
}

/** What a `WIFI:` text says; [security] is null for a [code] the template does not know. */
internal data class WifiReading(
    val ssid: String,
    val code: String,
    val security: WifiSecurity?,
    val password: String,
    val hidden: Boolean,
) {
    /** The security as shown to the user: the known title, else the code as written. */
    val securityTitle: String get() = security?.title ?: code
}
