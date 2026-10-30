package dev.sluide.converting.data

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.TextFormat
import dev.sluide.converting.WriteResult

/**
 * A format of the [DataFamily]. Stateless and thread-safe. The family handles blank text (no documents) and content
 * without documents (empty text), so [read] and [write] only see the rest.
 */
internal interface DataFormat : TextFormat {
    /** How sure it is that [text] (not blank) is in this format, 1..100, or `null` if it does not read it. */
    fun detect(text: String): Int?

    val readOptions: List<ConversionOption>
    val writeOptions: List<ConversionOption>

    fun read(
        text: String,
        options: OptionValues,
    ): ReadResult<DataContent>

    fun write(
        content: DataContent,
        options: OptionValues,
    ): WriteResult
}
