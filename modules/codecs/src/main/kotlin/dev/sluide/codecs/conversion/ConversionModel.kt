package dev.sluide.codecs.conversion

import dev.sluide.codecs.codec.CodecId
import dev.sluide.codecs.codec.Decoded
import dev.sluide.codecs.codec.HashValue
import dev.sluide.codecs.detect.CodecDetector
import dev.sluide.codecs.detect.FileKind
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Clock

/**
 * The state behind the Codecs tool: two editable sides, the one edited last is the source and the other follows it.
 * Not thread-safe; the view calls it on the FX thread and reads the state in [onChange].
 */
internal class ConversionModel(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    var settings: CodecSettings = CodecSettings()
        private set
    var source: Side = Side.TEXT
        private set
    var text: TextContent = TextContent.Text("")
        private set
    var encoded: String = ""
        private set
    var hashes: List<HashValue> = emptyList()
        private set
    var jwt: JwtView? = null
        private set
    var textStatus: Status = Status.Info("")
        private set
    var encodedStatus: Status = Status.Info("")
        private set

    private var detectionHint: Hint? = null
    private var compressionHint: Hint? = null
    private var fileName: String? = null

    val hint: Hint? get() = chooseHint(detectionHint, compressionHint)

    /** Called once after every state change. */
    var onChange: () -> Unit = {}

    /**
     * An edit the view cannot make, because it shows that side read-only (the text side for JWT, the encoded side for
     * Hash), is ignored: it changes nothing and does not call [onChange].
     */
    fun editText(text: String) {
        if (settings.codec == CodecId.JWT) return
        setText(TextContent.Text(text))
        fileName = null
        finish()
    }

    /** Ignored for Hash like [editText] is for JWT; [pasted] text is also checked for another codec. */
    fun editEncoded(
        text: String,
        pasted: Boolean = false,
    ) {
        if (settings.codec == CodecId.HASH) return
        setEncoded(text)
        recompute()
        if (pasted) detect(text)
        onChange()
    }

    fun updateSettings(change: (CodecSettings) -> CodecSettings) {
        settings = change(settings)
        when (settings.codec) {
            CodecId.HASH -> source = Side.TEXT
            CodecId.JWT -> source = Side.ENCODED
            else -> Unit
        }
        detectionHint = null
        recompute()
        onChange()
    }

    fun applyHint() {
        hint?.let { updateSettings(it.apply) }
    }

    /** "Open Selection in Codecs": switches to the detected codec, else the text becomes the text side. */
    fun open(text: String) {
        val (value, detection) =
            unquoted(text)?.let { inner -> CodecDetector.detect(inner)?.let { inner to it } }
                ?: (text to CodecDetector.detect(text))
        if (detection != null) {
            settings = settings.copy(codec = detection.codec)
            setEncoded(value)
        } else {
            if (settings.codec == CodecId.JWT) settings = settings.copy(codec = CodecId.BASE64)
            setText(TextContent.Text(text))
            fileName = null
        }
        finish()
    }

    /** Returns null on success, else why the file was not opened (the state stays as it was). */
    fun openFile(file: Path): String? {
        val name = file.fileName?.toString() ?: file.toString()
        val bytes =
            try {
                if (Files.size(file) > MAX_FILE_SIZE) return "$name is larger than 1 MiB"
                Files.readAllBytes(file)
            } catch (_: NoSuchFileException) {
                return "Cannot read $name: file not found"
            } catch (_: AccessDeniedException) {
                return "Cannot read $name: access denied"
            } catch (e: IOException) {
                return "Cannot read $name: ${e.message ?: e.javaClass.simpleName}"
            }
        if (settings.codec == CodecId.JWT) settings = settings.copy(codec = CodecId.BASE64)
        val asText = asText(bytes, settings.charset)
        setText(if (asText != null) TextContent.Text(asText) else TextContent.Bytes(bytes, FileKind.of(bytes), name))
        fileName = name
        finish()
        return null
    }

    fun editBytesAsText() {
        val bytes = (text as? TextContent.Bytes)?.bytes ?: return
        setText(TextContent.Text(String(bytes, settings.charset)))
        finish()
    }

    /** The bytes of the text side for "Save", null when the text cannot be written in the charset. */
    fun bytesToSave(): ByteArray? = (contentBytes(text, settings.charset) as? Decoded.Value)?.value

    /** Empties [side] and makes it the source, so the other side becomes empty too. */
    fun clear(side: Side) {
        val forced =
            when (settings.codec) {
                CodecId.HASH -> Side.TEXT
                CodecId.JWT -> Side.ENCODED
                else -> side
            }
        if (forced == Side.TEXT) setText(TextContent.Text("")) else setEncoded("")
        fileName = null
        finish()
    }

    /** The text inside one pair of quotes (selections in code often include them), or null. */
    private fun unquoted(text: String): String? {
        val t = text.trim()
        return if (t.length >= 2 && t.first() in QUOTES && t.last() == t.first()) t.substring(1, t.length - 1) else null
    }

    private fun setText(content: TextContent) {
        source = Side.TEXT
        text = content
        detectionHint = null
    }

    private fun setEncoded(value: String) {
        source = Side.ENCODED
        encoded = value
        detectionHint = null
        fileName = null
    }

    private fun finish() {
        recompute()
        onChange()
    }

    private fun detect(pasted: String) {
        val detection = CodecDetector.detect(pasted) ?: return
        if (detection.codec == settings.codec) return
        if (!detection.strong && encodedStatus !is Status.Error) return
        detectionHint = Hint("Looks like ${detection.description}", "Switch") { it.copy(codec = detection.codec) }
    }

    private fun recompute() {
        compressionHint = null
        if (settings.codec != CodecId.HASH) hashes = emptyList()
        if (settings.codec != CodecId.JWT) jwt = null
        if (source == Side.TEXT) fromText() else fromEncoded()
    }

    private fun fromText() {
        when (val r = encode(settings, text, fileName)) {
            is EncodeResult.Done -> {
                encoded = r.encoded
                hashes = r.hashes
                textStatus = Status.Info(describe(text, settings.charset))
                encodedStatus = Status.Info(r.info)
            }

            is EncodeResult.Failed -> {
                textStatus = r.error
            }
        }
    }

    private fun fromEncoded() {
        when (val r = decode(settings, encoded, clock)) {
            is DecodeResult.Done -> {
                text = r.text
                jwt = r.jwt
                compressionHint = r.compressionHint
                textStatus = Status.Info(describe(r.text, settings.charset))
                encodedStatus = Status.Info(r.info)
            }

            is DecodeResult.Failed -> {
                encodedStatus = r.error
            }
        }
    }

    companion object {
        const val MAX_FILE_SIZE = 1024 * 1024
        private const val QUOTES = "\"'`"
    }
}
