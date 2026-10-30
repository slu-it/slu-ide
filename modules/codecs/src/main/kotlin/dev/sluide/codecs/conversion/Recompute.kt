package dev.sluide.codecs.conversion

import dev.sluide.codecs.binary.grouped
import dev.sluide.codecs.codec.BytePipeline
import dev.sluide.codecs.codec.CodecId
import dev.sluide.codecs.codec.Compression
import dev.sluide.codecs.codec.Decoded
import dev.sluide.codecs.codec.HashValue
import dev.sluide.codecs.codec.Hashes
import dev.sluide.codecs.codec.Jwt
import dev.sluide.codecs.codec.LineByLine
import dev.sluide.codecs.codec.LinesResult
import dev.sluide.codecs.detect.FileKind
import java.net.URLConnection
import java.nio.charset.Charset
import java.time.Clock

/** The text side turned into the encoded side, or why that failed. */
internal sealed interface EncodeResult {
    /** [info] is the status of the encoded side. */
    class Done(
        val encoded: String,
        val hashes: List<HashValue>,
        val info: String,
    ) : EncodeResult

    class Failed(
        val error: Status.Error,
    ) : EncodeResult
}

/** The encoded side turned into the text side, or why that failed. [compressionHint] is a gzip or zlib suggestion. */
internal sealed interface DecodeResult {
    class Done(
        val text: TextContent,
        val jwt: JwtView?,
        val info: String,
        val compressionHint: Hint?,
    ) : DecodeResult

    class Failed(
        val error: Status.Error,
    ) : DecodeResult
}

private const val MAX_LISTED_LINES = 5
private const val BINARY_ERROR = "Binary data cannot be escaped; pick a byte codec or Hash"

private inline fun <T, R> Decoded<T>.then(next: (T) -> Decoded<R>): Decoded<R> =
    when (this) {
        is Decoded.Value -> next(value)
        is Decoded.Error -> this
    }

/**
 * The text for [bytes] when they are valid in [charset], hold no NUL and are no known binary format; binary data
 * otherwise. A zlib header is printable ASCII ("x^"), so it does not make the bytes binary by itself.
 */
internal fun asText(
    bytes: ByteArray,
    charset: Charset,
): String? {
    val kind = FileKind.of(bytes)
    if (kind != null && kind != FileKind.ZLIB) return null
    return BytePipeline.decodeText(bytes, charset)?.takeIf { '\u0000' !in it }
}

/** The hint to show: a detection (the user pasted something else) beats the compression hint. */
internal fun chooseHint(
    detection: Hint?,
    compression: Hint?,
): Hint? = detection ?: compression

/** The bytes behind the text side: the binary data, or the text in [charset]. */
internal fun contentBytes(
    content: TextContent,
    charset: Charset,
): Decoded<ByteArray> =
    when (content) {
        is TextContent.Bytes -> Decoded.Value(content.bytes)
        is TextContent.Text -> BytePipeline.encodeText(content.text, charset)
    }

private fun count(
    n: Int,
    unit: String,
) = "${n.grouped()} $unit" + if (n == 1) "" else "s"

private fun chars(text: String) = count(text.codePointCount(0, text.length), "char")

/** The status line of the text side: "11 chars · 11 bytes", "Binary data · 2 345 bytes · PNG image". */
internal fun describe(
    content: TextContent,
    charset: Charset,
): String =
    when (content) {
        is TextContent.Bytes -> {
            listOfNotNull("Binary data", count(content.bytes.size, "byte"), content.kind?.title).joinToString(" · ")
        }

        is TextContent.Text -> {
            val bytes = (BytePipeline.encodeText(content.text, charset) as? Decoded.Value)?.value
            when {
                content.text.isEmpty() -> ""
                bytes == null -> chars(content.text)
                else -> "${chars(content.text)} · ${count(bytes.size, "byte")}"
            }
        }
    }

/** The MIME type of a data URI: the compressed format when compressing, else the file kind, file name or text. */
private fun dataUriMime(
    settings: CodecSettings,
    bytes: ByteArray,
    isText: Boolean,
    fileName: String?,
): String? {
    if (settings.codec != CodecId.BASE64 || !settings.base64.dataUri) return null
    return when (settings.compression) {
        Compression.GZIP -> {
            FileKind.GZIP.mime
        }

        Compression.ZLIB -> {
            FileKind.ZLIB.mime
        }

        Compression.DEFLATE -> {
            "application/octet-stream"
        }

        Compression.NONE -> {
            (if (isText) null else FileKind.of(bytes)?.mime)
                ?: fileName?.let { URLConnection.guessContentTypeFromName(it) }
                ?: if (isText) "text/plain;charset=${settings.charset.name()}" else "application/octet-stream"
        }
    }
}

private fun encodeBytes(
    settings: CodecSettings,
    bytes: ByteArray,
    isText: Boolean,
    fileName: String?,
): Decoded<String> {
    val codec = settings.byteCodec(dataUriMime(settings, bytes, isText, fileName))
    return checkNotNull(codec) { "${settings.codec} is no byte codec" }.encode(
        BytePipeline.compress(bytes, settings.compression),
    )
}

private fun linesError(result: LinesResult): Status.Error {
    val failed = result.failedLines
    val listed = failed.take(MAX_LISTED_LINES).joinToString(", ") + if (failed.size > MAX_LISTED_LINES) ", …" else ""
    val label = if (failed.size == 1) "Line" else "Lines"
    val first = result.firstError
    return Status.Error("$label $listed: ${first?.message}", first?.position)
}

private fun Decoded.Error.toStatus() = Status.Error(message, position)

/** Text to encoded side; [fileName] is the opened file that gives a data URI its MIME type. */
internal fun encode(
    settings: CodecSettings,
    content: TextContent,
    fileName: String?,
): EncodeResult {
    if (settings.codec == CodecId.HASH) return hash(settings, content)
    if (settings.codec == CodecId.JWT) return EncodeResult.Failed(Status.Error("A JWT cannot be written", null))
    val textCodec = settings.textCodec()
    val encodeLine: (String) -> Decoded<String> =
        if (textCodec != null) {
            textCodec::encode
        } else {
            { line ->
                BytePipeline.encodeText(line, settings.charset).then { encodeBytes(settings, it, true, fileName) }
            }
        }

    fun encodeBinary(bytes: ByteArray): Decoded<String> =
        if (textCodec != null) Decoded.Error(BINARY_ERROR) else encodeBytes(settings, bytes, false, fileName)
    val result: Decoded<String> =
        when (content) {
            is TextContent.Bytes -> {
                encodeBinary(content.bytes)
            }

            is TextContent.Text -> {
                if (settings.lineByLine && settings.lineByLineApplies) {
                    val lines = LineByLine.convert(content.text, encodeLine)
                    if (lines.failedLines.isNotEmpty()) return EncodeResult.Failed(linesError(lines))
                    Decoded.Value(lines.text)
                } else {
                    encodeLine(content.text)
                }
            }
        }
    return when (result) {
        is Decoded.Value -> {
            EncodeResult.Done(
                result.value,
                emptyList(),
                if (result.value.isEmpty()) "" else chars(result.value),
            )
        }

        is Decoded.Error -> {
            EncodeResult.Failed(result.toStatus())
        }
    }
}

private fun hash(
    settings: CodecSettings,
    content: TextContent,
): EncodeResult {
    if (content is TextContent.Text && content.text.isEmpty()) return EncodeResult.Done("", emptyList(), "")
    val bytes =
        when (val b = contentBytes(content, settings.charset)) {
            is Decoded.Value -> b.value
            is Decoded.Error -> return EncodeResult.Failed(b.toStatus())
        }
    val key =
        settings.hash.key
            .toByteArray(Charsets.UTF_8)
            .takeIf { it.isNotEmpty() }
    val rows = Hashes.compute(bytes, key, settings.hash.output)
    val encoded = rows.joinToString("\n") { "${it.algorithm.title}  ${it.value}" }
    return EncodeResult.Done(encoded, rows, if (key != null) "HMAC with key" else "${rows.size} hashes")
}

private fun compressionHint(
    settings: CodecSettings,
    content: TextContent,
): Hint? {
    // Only binary content can be a compressed stream; a text that starts with "x^" is just text.
    if (settings.compression != Compression.NONE || content !is TextContent.Bytes) return null
    return when (content.kind) {
        FileKind.GZIP -> Hint("Looks gzip-compressed", "Gunzip") { it.copy(compression = Compression.GZIP) }
        FileKind.ZLIB -> Hint("Looks zlib-compressed", "Inflate") { it.copy(compression = Compression.ZLIB) }
        else -> null
    }
}

/** Encoded to text side. */
internal fun decode(
    settings: CodecSettings,
    encoded: String,
    clock: Clock,
): DecodeResult {
    if (encoded.isEmpty()) return DecodeResult.Done(TextContent.Text(""), null, "", null)
    if (settings.codec == CodecId.JWT) return decodeJwt(encoded, clock)
    val valid = "Valid ${settings.codec.title}"
    val byteCodec = settings.byteCodec(null)
    val textCodec = settings.textCodec()
    if (settings.lineByLine && settings.lineByLineApplies) {
        val decodeLine: (String) -> Decoded<String> =
            if (textCodec != null) {
                textCodec::decode
            } else {
                { line ->
                    checkNotNull(byteCodec).decode(line).then { raw ->
                        BytePipeline.decompress(raw, settings.compression).then { bytes ->
                            asText(bytes, settings.charset)?.let { Decoded.Value(it) }
                                ?: Decoded.Error("Line is binary data")
                        }
                    }
                }
            }
        val lines = LineByLine.convert(encoded, decodeLine)
        if (lines.failedLines.isNotEmpty()) return DecodeResult.Failed(linesError(lines))
        return DecodeResult.Done(TextContent.Text(lines.text), null, valid, null)
    }
    if (textCodec != null) {
        return when (val r = textCodec.decode(encoded)) {
            is Decoded.Value -> DecodeResult.Done(TextContent.Text(r.value), null, valid, null)
            is Decoded.Error -> DecodeResult.Failed(r.toStatus())
        }
    }
    val raw =
        when (val r = checkNotNull(byteCodec) { "${settings.codec} has no codec" }.decode(encoded)) {
            is Decoded.Value -> r.value
            is Decoded.Error -> return DecodeResult.Failed(r.toStatus())
        }
    val bytes =
        when (val r = BytePipeline.decompress(raw, settings.compression)) {
            is Decoded.Value -> r.value
            is Decoded.Error -> return DecodeResult.Failed(r.toStatus())
        }
    val text =
        asText(bytes, settings.charset)?.let { TextContent.Text(it) }
            ?: TextContent.Bytes(bytes, FileKind.of(bytes), null)
    return DecodeResult.Done(text, null, valid, compressionHint(settings, text))
}

private fun decodeJwt(
    encoded: String,
    clock: Clock,
): DecodeResult =
    when (val r = Jwt.decode(encoded)) {
        is Decoded.Error -> {
            DecodeResult.Failed(r.toStatus())
        }

        is Decoded.Value -> {
            val view = jwtView(r.value, clock)
            val info = listOfNotNull("Valid JWT", expiredText(view, clock)).joinToString(" · ")
            DecodeResult.Done(TextContent.Text(view.summary), view, info, null)
        }
    }
