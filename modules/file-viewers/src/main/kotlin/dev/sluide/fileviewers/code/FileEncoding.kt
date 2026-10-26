package dev.sluide.fileviewers.code

import dev.sluide.textediting.TextEncoding
import dev.sluide.textediting.decodeText
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import java.io.IOException
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The encoding a [CodeView] saves its file in (ADR 0098): the one the file was read in, until the user chooses to
 * [convert] a Windows-1252 file to UTF-8, which stays [converting] (and the view dirty) until the next save.
 */
internal class FileEncoding(
    encoding: TextEncoding,
) {
    /** What [encode] writes. */
    var encoding: TextEncoding = encoding
        private set

    private val convertingWrapper = ReadOnlyBooleanWrapper(false)

    /** Whether the file was [convert]ed to UTF-8 and not saved since, so that the file on disk is not yet. */
    val converting: ReadOnlyBooleanProperty = convertingWrapper.readOnlyProperty

    /** Whether to offer converting the file to UTF-8: it is Windows-1252 and not converted yet. */
    val convertible: Boolean get() = encoding == TextEncoding.WINDOWS_1252

    /** Saves in UTF-8 from now on; the view has unsaved changes until then. */
    fun convert() {
        if (!convertible) return
        encoding = TextEncoding.UTF_8
        convertingWrapper.set(true)
    }

    /**
     * The file was read again in [disk] (`null`: it does not decode, so it is not saved, and UTF-8 stands in); the view
     * shows its text now, and a pending conversion is dropped with the text it was for.
     */
    fun read(disk: TextEncoding?) {
        encoding = disk ?: TextEncoding.UTF_8
        convertingWrapper.set(false)
    }

    /**
     * The file on disk still has the saved text, in [disk], e.g. after it was saved or touched: the encoding follows
     * it, unless a conversion to UTF-8 is pending and the file is not UTF-8 yet.
     */
    fun unchanged(disk: TextEncoding?) {
        if (disk == null || (converting.get() && disk != TextEncoding.UTF_8)) return
        read(disk)
    }

    /** The file was saved: it has the encoding now. */
    fun saved() = convertingWrapper.set(false)

    /**
     * [text] in [encoding]; an [IOException] naming the first character it cannot store (with its line, from 1),
     * so that saving never writes replacement characters.
     */
    fun encode(text: String): ByteArray =
        try {
            val buffer = encoding.charset.newEncoder().encode(CharBuffer.wrap(text))
            ByteArray(buffer.remaining()).also(buffer::get)
        } catch (e: CharacterCodingException) {
            val (character, line) = firstUnencodable(text, encoding) ?: throw e
            val codePoint = "U+%04X".format(character.codePointAt(0))
            val hint = if (encoding == TextEncoding.UTF_8) "" else " Convert the file to UTF-8 first."
            throw IOException(
                "Line $line contains '$character' ($codePoint), which ${encoding.label} cannot store.$hint",
            )
        }
}

/** The first character (one code point) of [text] that [encoding] cannot store, with its line counted from 1. */
internal fun firstUnencodable(
    text: String,
    encoding: TextEncoding,
): Pair<String, Int>? {
    val encoder = encoding.charset.newEncoder()
    var line = 1
    var index = 0
    while (index < text.length) {
        val end = text.offsetByCodePoints(index, 1)
        val character = text.substring(index, end)
        if (!encoder.canEncode(character)) return character to line
        // `\n`, `\r\n` or `\r` (the text has the file's line endings).
        if (text[index] == '\n' || (text[index] == '\r' && text.getOrNull(end) != '\n')) line++
        index = end
    }
    return null
}

/**
 * A file's content as read, [raw] with its line endings, and the [encoding] it was decoded from; not [valid] (no
 * encoding) if it shows replacement characters.
 */
internal class FileContent(
    val raw: String,
    val encoding: TextEncoding?,
) {
    val valid: Boolean get() = encoding != null
}

/** The content of [file]: decoded ([decodeText]), or as UTF-8 with replacement characters if it does not decode. */
internal fun readContent(file: Path): FileContent {
    val bytes = Files.readAllBytes(file)
    val decoded = decodeText(bytes)
    return FileContent(decoded?.text ?: String(bytes, Charsets.UTF_8), decoded?.encoding)
}
