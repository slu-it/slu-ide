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

    override fun detect(text: String): List<FormatMatch> =
        if (text.isBlank()) {
            emptyList()
        } else {
            dataFormats
                .mapNotNull { format -> guarded({ null }) { format.detect(text) }?.let { FormatMatch(format, it) } }
                .sortedByDescending(FormatMatch::score)
        }

    override fun readOptions(format: TextFormat): List<ConversionOption> = dataFormat(format).readOptions

    override fun writeOptions(format: TextFormat): List<ConversionOption> = dataFormat(format).writeOptions

    override fun read(
        text: String,
        format: TextFormat,
        options: OptionValues,
    ): ReadResult<DataContent> {
        val dataFormat = dataFormat(format)
        return if (text.isBlank()) {
            ReadResult.Read(DataContent(emptyList()), emptyList())
        } else {
            guarded({ ReadResult.Failed(it, null) }) { dataFormat.read(text, options) }
        }
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
