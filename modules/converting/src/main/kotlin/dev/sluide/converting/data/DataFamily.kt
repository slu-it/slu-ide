package dev.sluide.converting.data

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.FormatFamily
import dev.sluide.converting.FormatMatch
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.TextFormat
import dev.sluide.converting.WriteResult
import org.springframework.stereotype.Component

/** The built-in family of data formats, converting through a tree of [DataContent] documents (ADR 0115). */
@Component
internal class DataFamily(
    private val dataFormats: List<DataFormat>,
) : FormatFamily<DataContent> {
    /** The constructor Spring uses. */
    constructor() : this(DataFormats.all)

    override val name = "Data"
    override val formats: List<TextFormat> get() = dataFormats

    override fun detect(text: String): List<FormatMatch> {
        val content = withoutBom(text)
        return if (content.isBlank()) {
            emptyList()
        } else {
            dataFormats
                .mapNotNull { format -> guarded({ null }) { format.detect(content) }?.let { FormatMatch(format, it) } }
                .sortedByDescending(FormatMatch::score)
        }
    }

    override fun readOptions(format: TextFormat): List<ConversionOption> = dataFormat(format).readOptions

    override fun writeOptions(format: TextFormat): List<ConversionOption> = dataFormat(format).writeOptions

    override fun read(
        text: String,
        format: TextFormat,
        options: OptionValues,
    ): ReadResult<DataContent> {
        val dataFormat = dataFormat(format)
        val content = withoutBom(text)
        if (content.isBlank()) return ReadResult.Read(DataContent(emptyList()), emptyList())
        val read = guarded({ ReadResult.Failed(it, null) }) { dataFormat.read(content, options) }
        if (read is ReadResult.Read && read.content.documents.any { isNestedBeyond(it, YamlReader.MAX_DEPTH) }) {
            return ReadResult.Failed("Nested more than ${YamlReader.MAX_DEPTH} levels deep", null)
        }
        val offset = (read as? ReadResult.Failed)?.offset ?: return read
        return read.copy(offset = offset + text.length - content.length)
    }

    override fun write(
        content: DataContent,
        format: TextFormat,
        options: OptionValues,
    ): WriteResult {
        val dataFormat = dataFormat(format)
        return if (content.documents.isEmpty()) {
            WriteResult.Written("", emptyList())
        } else {
            guarded({ WriteResult.Failed(it) }) { dataFormat.write(content, options) }
        }
    }

    private fun dataFormat(format: TextFormat): DataFormat =
        requireNotNull(
            dataFormats.firstOrNull { it.id == format.id },
        ) { "${format.id} is not a format of the $name family" }

    /** A byte order mark is no content; editors keep it when a file has one. Offsets still count it. */
    private fun withoutBom(text: String) = text.removePrefix("\uFEFF")

    /** Families never throw: a format's unforeseen exception becomes [failed] with its message. */
    private inline fun <R> guarded(
        failed: (String) -> R,
        call: () -> R,
    ): R =
        try {
            call()
        } catch (e: RuntimeException) {
            failed(sentence(e.message))
        }
}
