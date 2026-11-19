package dev.sluide.ide

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.FormatFamily
import dev.sluide.converting.FormatMatch
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.TextFormat
import dev.sluide.converting.WriteResult

/** A format that maps to the text type TOML (extension `toml`) and is detected by [ShoutDetectingFamily]. */
private object ShoutTomlFormat : TextFormat {
    override val id = "shout-toml"
    override val name = "Shout TOML"
    override val language: String? = null
    override val extensions = listOf("toml")
}

/**
 * A family that detects the texts that start with "shout" as its one format with score 90 and converts nothing: the
 * window tests use it to see that the explorer's Paste Text and File → New Scratch from Clipboard detect with
 * Converting's families (the real data family is internal). Its format is in [formats], as a real family's is.
 */
internal object ShoutDetectingFamily : FormatFamily<String> {
    override val name = "Shout detecting"
    override val formats: List<TextFormat> = listOf(ShoutTomlFormat)

    override fun detect(text: String) =
        if (text.startsWith("shout")) listOf(FormatMatch(ShoutTomlFormat, 90)) else emptyList<FormatMatch>()

    override fun readOptions(format: TextFormat) = emptyList<ConversionOption>()

    override fun writeOptions(format: TextFormat) = emptyList<ConversionOption>()

    override fun read(
        text: String,
        format: TextFormat,
        options: OptionValues,
    ): ReadResult<String> = ReadResult.Read(text, emptyList())

    override fun write(
        content: String,
        format: TextFormat,
        options: OptionValues,
    ): WriteResult = WriteResult.Written(content, emptyList())
}
