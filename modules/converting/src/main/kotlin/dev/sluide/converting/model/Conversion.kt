package dev.sluide.converting.model

import dev.sluide.converting.FormatFamily
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.TextFormat
import dev.sluide.converting.WriteResult

/** What a family read from a text, kept to write it in another format of the family. Immutable. */
internal class ReadContent<T : Any>(
    private val family: FormatFamily<T>,
    private val content: T,
    val notes: List<String>,
) {
    fun write(
        format: TextFormat,
        options: OptionValues,
    ): WriteResult = family.write(content, format, options)
}

private fun <T : Any> FormatFamily<T>.readContent(
    text: String,
    format: TextFormat,
    options: OptionValues,
): ReadResult<ReadContent<*>> =
    when (val read = read(text, format, options)) {
        is ReadResult.Failed -> read
        is ReadResult.Read -> ReadResult.Read(ReadContent(this, read.content, read.notes), read.notes)
    }

/**
 * The model's state a conversion needs, taken on the FX thread. [previousSource] is the source's format shown so far,
 * [targetPinned] whether the user pinned the [target]'s format, [history] the formats used as target, oldest first.
 */
internal data class ConversionInput(
    val text: String,
    val pick: Pick,
    val previousSource: TextFormat?,
    val target: TextFormat?,
    val targetPinned: Boolean,
    val history: List<TextFormat>,
    val lastPinned: TextFormat?,
    val options: OptionValues,
) {
    /** The source's format the user pinned, if any. */
    val pinned: TextFormat? get() = (pick as? Pick.Pinned)?.format
}

/** What converting the source gave: its format, the target's format, the [step] reached and a [hint]. */
internal data class ConversionOutcome(
    val source: TextFormat?,
    val target: TextFormat?,
    val step: Step,
    val hint: TextFormat? = null,
) {
    sealed interface Step {
        /** A blank source: the target becomes empty. */
        data object Blank : Step

        data object Undetected : Step

        data class ReadFailed(
            val message: String,
            val position: TextPosition?,
        ) : Step

        data class Written(
            val text: String,
            val notes: List<String>,
            val content: ReadContent<*>,
        ) : Step

        data class WriteFailed(
            val message: String,
            val content: ReadContent<*>,
        ) : Step
    }
}

/** Detects the source's format if it is not pinned, chooses the target's format and converts; called off the FX thread. */
internal fun convert(
    families: Families,
    input: ConversionInput,
): ConversionOutcome {
    val pinned = input.pinned
    val source = pinned ?: families.detect(input.text, input.previousSource, input.lastPinned)
    val target =
        families.target(
            source,
            sourceChanged = source?.id != input.previousSource?.id,
            current = input.target,
            pinned = input.targetPinned,
            history = input.history,
        )
    if (input.text.isBlank()) return ConversionOutcome(source, target, ConversionOutcome.Step.Blank)
    if (source == null || target == null) return ConversionOutcome(source, target, ConversionOutcome.Step.Undetected)
    return when (val read = families.familyOf(source).readContent(input.text, source, input.options)) {
        is ReadResult.Failed -> {
            val position = read.offset?.let { offset -> positionOf(input.text, offset) }
            val hint = pinned?.let { families.detect(input.text, null, input.lastPinned, except = it) }
            ConversionOutcome(source, target, ConversionOutcome.Step.ReadFailed(read.message, position), hint)
        }

        is ReadResult.Read -> {
            val step =
                when (val written = read.content.write(target, input.options)) {
                    is WriteResult.Failed -> {
                        ConversionOutcome.Step.WriteFailed(written.message, read.content)
                    }

                    is WriteResult.Written -> {
                        ConversionOutcome.Step.Written(written.text, read.notes + written.notes, read.content)
                    }
                }
            ConversionOutcome(source, target, step)
        }
    }
}

private fun positionOf(
    text: String,
    offset: Int,
): TextPosition {
    val (line, column) = lineColumn(text, offset)
    return TextPosition(offset, line, column)
}
