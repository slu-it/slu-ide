package dev.sluide.codecs.codec

import tools.jackson.core.JacksonException
import tools.jackson.core.util.DefaultIndenter
import tools.jackson.core.util.DefaultPrettyPrinter
import tools.jackson.core.util.Separators
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.Base64

/** A time claim of a JWT (`exp`, `iat`, `nbf`). */
data class JwtTime(
    val claim: String,
    val instant: Instant,
)

/** A JWT split into its readable parts; the signature is not verified. */
data class DecodedJwt(
    val header: String,
    val payload: String,
    val algorithm: String?,
    val times: List<JwtTime>,
    val signed: Boolean,
)

/** Decodes (never verifies) JWTs. */
internal object Jwt {
    private val bearer = Regex("^\\s*(?:bearer\\s+)?", RegexOption.IGNORE_CASE)
    private val printer =
        DefaultPrettyPrinter(
            Separators.createDefaultInstance().withObjectNameValueSpacing(Separators.Spacing.AFTER),
        ).withObjectIndenter(DefaultIndenter("  ", "\n"))
            .withArrayIndenter(DefaultIndenter("  ", "\n"))
    private val mapper: JsonMapper =
        JsonMapper
            .builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            // Keeps the digits of numbers as written (1.10 stays 1.10) instead of rounding them to a double.
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .defaultPrettyPrinter(printer)
            .build()
    private val timeClaims = listOf("exp", "iat", "nbf")

    fun decode(token: String): Decoded<DecodedJwt> {
        val start =
            bearer
                .find(token)
                ?.range
                ?.last
                ?.plus(1) ?: 0
        val end = token.trimEnd().length
        if (start >= end) return Decoded.Error("A JWT has three parts separated by dots")
        val text = token.substring(start, end)
        val parts = text.split('.')
        if (parts.size == JWE_PARTS) return Decoded.Error("Encrypted JWT (JWE), cannot be decoded")
        if (parts.size != JWT_PARTS) return Decoded.Error("A JWT has three parts separated by dots")

        val headerBytes = base64Url(parts[0], start, "header").let { if (it is Decoded.Error) return it else bytes(it) }
        val payloadOffset = start + parts[0].length + 1
        val payloadBytes =
            base64Url(parts[1], payloadOffset, "payload").let { if (it is Decoded.Error) return it else bytes(it) }

        val headerText = utf8(headerBytes) ?: return invalidUtf8("header", start)
        val header =
            parseObject(headerText) ?: return Decoded.Error("The header is not a JSON object", start)
        val signatureOffset = payloadOffset + parts[1].length + 1
        val signature = base64Url(parts[2], signatureOffset, "signature")
        if (signature is Decoded.Error) return signature
        val payloadText = utf8(payloadBytes) ?: return invalidUtf8("payload", payloadOffset)
        val payloadJson = parse(payloadText)

        val algorithm = header.get("alg")?.takeIf { it.isString }?.stringValue()
        return Decoded.Value(
            DecodedJwt(
                header = mapper.writer().writeValueAsString(header),
                payload = payloadJson?.let { mapper.writer().writeValueAsString(it) } ?: payloadText,
                algorithm = algorithm,
                times = if (payloadJson != null && payloadJson.isObject) times(payloadJson) else emptyList(),
                signed = parts[2].isNotEmpty(),
            ),
        )
    }

    private fun bytes(decoded: Decoded<ByteArray>): ByteArray = (decoded as Decoded.Value).value

    private fun invalidUtf8(
        what: String,
        position: Int,
    ) = Decoded.Error("The $what is not valid UTF-8", position)

    private fun base64Url(
        part: String,
        offset: Int,
        what: String,
    ): Decoded<ByteArray> {
        val data = part.trimEnd('=')
        val bad = data.indexOfFirst { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }
        if (bad >= 0) return Decoded.Error("Invalid Base64url character '${data[bad]}' in the $what", offset + bad)
        if (data.length % 4 == 1) return Decoded.Error("Invalid Base64url length in the $what", offset)
        return Decoded.Value(Base64.getUrlDecoder().decode(data.padEnd((data.length + 3) / 4 * 4, '=')))
    }

    private fun utf8(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

    private fun parse(text: String): JsonNode? =
        try {
            mapper.readTree(text)?.takeIf { !it.isMissingNode }
        } catch (_: JacksonException) {
            null
        }

    private fun parseObject(text: String): JsonNode? = parse(text)?.takeIf { it.isObject }

    private fun times(payload: JsonNode): List<JwtTime> =
        timeClaims.mapNotNull { claim ->
            val node = payload.get(claim)
            if (node == null || !node.isNumber) return@mapNotNull null
            val millis = millis(node) ?: return@mapNotNull null
            JwtTime(claim, Instant.ofEpochMilli(millis))
        }

    /** Seconds (fractions allowed) as milliseconds, or null for a number that is infinite or beyond the time range. */
    private fun millis(node: JsonNode): Long? =
        try {
            val seconds = node.decimalValue()
            if (seconds.signum() == 0) return 0L
            // Below one millisecond: skipped before any scaling, 1e-200000000 would expand into a huge integer.
            if (seconds.precision() - seconds.scale() < MIN_SECONDS_MAGNITUDE) return null
            // Rejects 1e999999999 before it is expanded into a huge integer.
            if (seconds.precision() - seconds.scale() > MAX_SECONDS_DIGITS) {
                null
            } else {
                seconds
                    .multiply(BigDecimal(MILLIS))
                    .toBigInteger()
                    .longValueExact()
            }
        } catch (_: ArithmeticException) {
            null
        } catch (_: JacksonException) {
            null
        }

    private const val JWT_PARTS = 3
    private const val JWE_PARTS = 5
    private const val MILLIS = 1000
    private const val MAX_SECONDS_DIGITS = 15
    private const val MIN_SECONDS_MAGNITUDE = -3
}
