package dev.sluide.converting.ui

import dev.sluide.converting.FormatFamily
import dev.sluide.converting.TextFormat
import dev.sluide.converting.model.Pick
import dev.sluide.converting.model.Status
import dev.sluide.converting.model.TargetChoice

/** An entry of a side's format picker. */
internal sealed interface PickerEntry {
    /** The name of the family whose formats follow; shown when the picker offers formats of several families. */
    data class Header(
        val title: String,
    ) : PickerEntry

    /** A choice: Detect or a format; [disabled] when the target cannot hold the source in it. */
    data class Item(
        val text: String,
        val pick: Pick,
        val selected: Boolean,
        val disabled: Boolean = false,
    ) : PickerEntry
}

/**
 * The label of a side's format picker: the pinned format, "Detect: YAML" on a source that detects (just "Detect"
 * while nothing is detected), the shown format on a target that follows the suggestion.
 */
internal fun pickerLabel(
    isSource: Boolean,
    pick: Pick,
    format: TextFormat?,
): String =
    when {
        pick is Pick.Pinned -> pick.format.name
        format == null -> "Detect"
        isSource -> "Detect: ${format.name}"
        else -> format.name
    }

/**
 * The entries of a format picker: Detect, then [choices] (all formats on the source side, the target choices on the
 * target side), grouped under the name of their family when they come from more than one of [families]. An
 * unavailable format is disabled and says why ("TOML — needs an object at the top").
 */
internal fun pickerEntries(
    families: List<FormatFamily<*>>,
    choices: List<TargetChoice>,
    pick: Pick,
): List<PickerEntry> {
    val groups =
        families
            .map { family -> family to choices.filter { choice -> family.formats.any { it.id == choice.format.id } } }
            .filter { (_, inFamily) -> inFamily.isNotEmpty() }
    val pinned = (pick as? Pick.Pinned)?.format?.id
    return buildList {
        add(PickerEntry.Item("Detect", Pick.Detect, selected = pick == Pick.Detect))
        for ((family, inFamily) in groups) {
            if (groups.size > 1) add(PickerEntry.Header(family.name))
            for (choice in inFamily) {
                val text = choice.unavailable?.let { "${choice.format.name} — $it" } ?: choice.format.name
                val item =
                    PickerEntry.Item(
                        text,
                        Pick.Pinned(choice.format),
                        selected = choice.format.id == pinned,
                        disabled = choice.unavailable != null,
                    )
                add(item)
            }
        }
    }
}

/** The offset in the editor that a click on [status] of [text] selects, or null if the status is not clickable. */
internal fun clickOffset(
    status: Status,
    text: String,
): Int? = (status as? Status.Failure)?.position?.offset?.let { editorOffset(text, it) }

/**
 * [offset] into [text] as an offset into the editor showing it: editors turn `\r\n` into `\n`, so every `\r\n` before
 * [offset] counts one less.
 */
internal fun editorOffset(
    text: String,
    offset: Int,
): Int {
    val end = offset.coerceIn(0, text.length)
    var pairs = 0
    for (i in 0 until end) {
        if (text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') pairs++
    }
    return end - pairs
}

/** [text] as an editor holds it: `\r\n` and `\r` become `\n`. */
internal fun editorText(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

/** The extension of a scratch file opened from a side in [format]: its first one, else `txt`. */
internal fun scratchExtension(format: TextFormat?): String = format?.extensions?.firstOrNull() ?: "txt"
