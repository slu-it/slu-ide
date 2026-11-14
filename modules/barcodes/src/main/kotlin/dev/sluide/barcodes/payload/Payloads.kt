package dev.sluide.barcodes.payload

import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import com.google.zxing.client.result.AddressBookParsedResult
import com.google.zxing.client.result.CalendarParsedResult
import com.google.zxing.client.result.EmailAddressParsedResult
import com.google.zxing.client.result.GeoParsedResult
import com.google.zxing.client.result.ParsedResult
import com.google.zxing.client.result.ResultParser
import com.google.zxing.client.result.SMSParsedResult
import com.google.zxing.client.result.TelParsedResult
import com.google.zxing.client.result.URIParsedResult
import java.math.BigDecimal
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One labeled line of the structured view; a [secret] value is shown masked. */
internal data class PayloadField(
    val label: String,
    val value: String,
    val secret: Boolean = false,
)

/** What a barcode text is beyond plain text: a [kind] such as "Wi-Fi" and its labeled [fields]. */
internal data class Payload(
    val kind: String,
    val fields: List<PayloadField>,
)

/** Recognizes structured barcode texts (Wi-Fi, contact, link, ..); everything else is plain text. */
internal object Payloads {
    /**
     * Longest text [parse] looks at: no generatable code holds more than about 4.3 K characters, and ZXing's vCard
     * regex takes quadratic time on crafted input (32 KB: over a second on every keystroke).
     */
    internal const val MAX_PARSED_LENGTH = 8_192

    private val ABSOLUTE_URI = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val EIGHT_DIGITS = Regex("^\\d{8}$")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** The structured view of [text], or null for plain text (also for ISBN, product and other bare numbers). */
    fun parse(text: String): Payload? {
        if (text.length > MAX_PARSED_LENGTH) return null
        OtpAuth.parse(text)?.let { return it }
        if (text.trim().startsWith("otpauth:", ignoreCase = true)) return null
        if (text.startsWith("WIFI:")) return Wifi.read(text)?.let { wifi(it) }
        return try {
            map(text, ResultParser.parseResult(Result(text, null, null, BarcodeFormat.QR_CODE)))
        } catch (_: RuntimeException) {
            // A backstop only: no input has been found that makes ZXing throw.
            null
        }
    }

    /**
     * The trimmed [text] when it is exactly one absolute http or https URL with a host, else null. URLs with user
     * info (`https://google.com@evil.com/`) and internationalized host names (`https://bücher.de`) give null, so
     * the UI offers no button for them.
     */
    fun httpUrl(text: String): URI? {
        val trimmed = text.trim()
        // java.net.URI rejects control characters itself; the check is a backstop.
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri =
            try {
                URI(trimmed)
            } catch (_: URISyntaxException) {
                return null
            }
        val schemeOk = uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)
        return uri.takeIf { schemeOk && !it.host.isNullOrEmpty() && it.rawUserInfo == null }
    }

    private fun map(
        text: String,
        parsed: ParsedResult,
    ): Payload? =
        when (parsed) {
            is AddressBookParsedResult -> {
                contact(parsed)
            }

            is EmailAddressParsedResult -> {
                payload(
                    "Email",
                    "To" to parsed.tos.orEmpty().joinToString(", "),
                    "Subject" to parsed.subject,
                    "Body" to parsed.body,
                )
            }

            is TelParsedResult -> {
                payload("Phone", "Number" to parsed.number)
            }

            is SMSParsedResult -> {
                payload("SMS", "Number" to parsed.numbers.orEmpty().joinToString(", "), "Message" to parsed.body)
            }

            is GeoParsedResult -> {
                geo(parsed)
            }

            is CalendarParsedResult -> {
                calendar(parsed)
            }

            is URIParsedResult -> {
                // ZXing turns a bare "example.com" into "http://example.com"; that URI is not in the text, so it is
                // plain text to a user.
                val uri = parsed.uri.orEmpty()
                if (uri in text && ABSOLUTE_URI.containsMatchIn(uri)) {
                    payload("Web link", "URL" to parsed.uri, "Title" to parsed.title)
                } else {
                    null
                }
            }

            else -> {
                null
            }
        }

    private fun payload(
        kind: String,
        vararg fields: Pair<String, String?>,
    ): Payload? {
        val kept =
            fields.mapNotNull { (label, value) ->
                value?.takeIf { it.isNotBlank() }?.let { PayloadField(label, it) }
            }
        return if (kept.isEmpty()) null else Payload(kind, kept)
    }

    private fun wifi(wifi: WifiReading) =
        Payload(
            "Wi-Fi",
            listOfNotNull(
                PayloadField("Network", wifi.ssid),
                PayloadField("Security", wifi.securityTitle),
                wifi.password.takeIf { it.isNotEmpty() }?.let { PayloadField("Password", it, secret = true) },
                PayloadField("Hidden", "Yes").takeIf { wifi.hidden },
            ),
        )

    private fun contact(card: AddressBookParsedResult): Payload? {
        val fields = mutableListOf<PayloadField>()

        fun add(
            label: String,
            value: String?,
        ) {
            if (!value.isNullOrBlank()) fields += PayloadField(label, value)
        }
        add("Name", card.names?.firstOrNull())
        add("Organization", card.org)
        add("Title", card.title)
        card.phoneNumbers.orEmpty().forEach { add("Phone", it) }
        card.emails.orEmpty().forEach { add("Email", it) }
        add(
            "Address",
            card.addresses
                .orEmpty()
                .filter { it.isNotBlank() }
                .joinToString("; "),
        )
        card.urLs.orEmpty().forEach { add("Website", it) }
        add("Note", card.note)
        add(
            "Birthday",
            card.birthday?.let {
                if (EIGHT_DIGITS.matches(it)) "${it.take(4)}-${it.substring(4, 6)}-${it.substring(6)}" else it
            },
        )
        return if (fields.isEmpty()) null else Payload("Contact", fields)
    }

    private fun geo(geo: GeoParsedResult) =
        payload(
            "Location",
            "Latitude" to BigDecimal.valueOf(geo.latitude).stripTrailingZeros().toPlainString(),
            "Longitude" to BigDecimal.valueOf(geo.longitude).stripTrailingZeros().toPlainString(),
            "Altitude" to
                geo.altitude.takeIf { it != 0.0 }?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() },
            "Query" to geo.query?.removePrefix("q="),
        )

    private fun calendar(event: CalendarParsedResult) =
        payload(
            "Calendar event",
            "Summary" to event.summary,
            "Start" to moment(event.startTimestamp, event.isStartAllDay),
            "End" to moment(event.endTimestamp, event.isEndAllDay),
            "Location" to event.location,
            "Description" to event.description,
        )

    private fun moment(
        millis: Long,
        allDay: Boolean,
    ): String? {
        if (millis < 0) return null
        // ZXing reads a date without time as midnight GMT, so it is shown in UTC; timed values in the system zone.
        return if (allDay) {
            Instant
                .ofEpochMilli(millis)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .toString()
        } else {
            DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        }
    }
}
