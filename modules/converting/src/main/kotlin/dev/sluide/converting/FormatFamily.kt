package dev.sluide.converting

/** A format that reads a text, with how sure the detection is: 1..100, higher is surer. */
data class FormatMatch(
    val format: TextFormat,
    val score: Int,
)

/** What reading a text gave: its content with notes on what was dropped, or why it failed. */
sealed interface ReadResult<out T> {
    data class Read<T>(
        val content: T,
        val notes: List<String>,
    ) : ReadResult<T>

    /** [offset] is the char offset into the text where reading failed, if known. */
    data class Failed(
        val message: String,
        val offset: Int?,
    ) : ReadResult<Nothing>
}

/** What writing content gave: the text with notes on what was dropped or changed, or why the target cannot hold it. */
sealed interface WriteResult {
    data class Written(
        val text: String,
        val notes: List<String>,
    ) : WriteResult

    data class Failed(
        val message: String,
    ) : WriteResult
}

/**
 * Formats that convert into each other through a model [T]; formats of different families never meet (ADR 0115).
 * Families are Spring beans; implementations are stateless and thread-safe, as everything is called off the FX thread.
 * They never throw for any text or content: what cannot be read or written is a [ReadResult.Failed] or
 * [WriteResult.Failed], a text no format reads has no [FormatMatch]. Only a [TextFormat] of another family is refused
 * with an [IllegalArgumentException].
 */
interface FormatFamily<T : Any> {
    val name: String
    val formats: List<TextFormat>

    /** Whether the explorer's "Convert to" offers this family's formats for files; the data family does (ADR 0115). */
    val convertsFiles: Boolean get() = false

    /** Every format of this family that reads [text], with its score, surest first; blank text matches none. */
    fun detect(text: String): List<FormatMatch>

    fun readOptions(format: TextFormat): List<ConversionOption>

    fun writeOptions(format: TextFormat): List<ConversionOption>

    fun read(
        text: String,
        format: TextFormat,
        options: OptionValues,
    ): ReadResult<T>

    /** Writes [content] as [format] and leaves it unchanged: one read content is written in several formats. */
    fun write(
        content: T,
        format: TextFormat,
        options: OptionValues,
    ): WriteResult
}

/** What [convert] gave. */
sealed interface ConversionResult {
    /** [notes] are those of reading, then those of writing. */
    data class Converted(
        val text: String,
        val notes: List<String>,
    ) : ConversionResult

    /** [offset] is the char offset into the source text, if known. */
    data class ReadFailed(
        val message: String,
        val offset: Int?,
    ) : ConversionResult

    data class WriteFailed(
        val message: String,
    ) : ConversionResult
}

/** Reads [text] as [from] and writes it as [to]; callable on a family held as `FormatFamily<*>`. */
fun <T : Any> FormatFamily<T>.convert(
    text: String,
    from: TextFormat,
    to: TextFormat,
    options: OptionValues,
): ConversionResult =
    when (val read = read(text, from, options)) {
        is ReadResult.Failed -> {
            ConversionResult.ReadFailed(read.message, read.offset)
        }

        is ReadResult.Read -> {
            when (val written = write(read.content, to, options)) {
                is WriteResult.Failed -> ConversionResult.WriteFailed(written.message)
                is WriteResult.Written -> ConversionResult.Converted(written.text, read.notes + written.notes)
            }
        }
    }
