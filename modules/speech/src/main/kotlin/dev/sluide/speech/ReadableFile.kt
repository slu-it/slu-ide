package dev.sluide.speech

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** What Read Aloud takes of a file: the text, or what is wrong with it, readable for the user. */
internal sealed interface ReadableFile {
    data class Text(
        val text: String,
        val markdown: Boolean,
    ) : ReadableFile

    data class Unreadable(
        val message: String,
    ) : ReadableFile
}

internal const val MAX_READ_BYTES = 1024 * 1024

/** Reads [file] (UTF-8, up to 1 MiB); blocking, call it off the FX thread. `.md` and `.markdown` read as Markdown. */
internal fun readableFile(file: Path): ReadableFile {
    val name = file.fileName?.toString() ?: file.toString()
    return try {
        if (Files.size(file) > MAX_READ_BYTES) return ReadableFile.Unreadable("$name is larger than 1 MiB.")
        val bytes = Files.readAllBytes(file)
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        ReadableFile.Text(decoder.decode(ByteBuffer.wrap(bytes)).toString(), isMarkdownName(name))
    } catch (_: CharacterCodingException) {
        ReadableFile.Unreadable("$name is not UTF-8 text.")
    } catch (e: IOException) {
        ReadableFile.Unreadable("$name cannot be read: ${e.message ?: e.javaClass.simpleName}")
    }
}

/** Whether a file with this name is Markdown, by its extension. */
internal fun isMarkdownName(name: String?): Boolean {
    val lower = name?.lowercase() ?: return false
    return lower.endsWith(".md") || lower.endsWith(".markdown")
}
