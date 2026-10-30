package dev.sluide.scratches.picker

/** A row of a [FilterPicker]: a group [Header], never selected or picked, or an [Entry] to pick. */
internal sealed interface PickerRow<out T> {
    data class Header(
        val title: String,
    ) : PickerRow<Nothing>

    data class Entry<out T>(
        val item: T,
    ) : PickerRow<T>
}

/**
 * The rows for [items] in their order: without [headerOf] only entries, else a header before each run of items whose
 * header title differs from the item before.
 */
internal fun <T> pickerRows(
    items: List<T>,
    headerOf: ((T) -> String)?,
): List<PickerRow<T>> =
    buildList {
        var current: String? = null
        for (item in items) {
            val title = headerOf?.invoke(item)
            if (title != null && title != current) add(PickerRow.Header(title))
            current = title
            add(PickerRow.Entry(item))
        }
    }

/** The index of the first entry in [rows]; -1 if there is none. */
internal fun firstEntry(rows: List<PickerRow<*>>): Int = rows.indexOfFirst { it is PickerRow.Entry }

/** The index of the entry holding [item] in [rows], else of the first entry; -1 if there is none. */
internal fun <T> entryIndex(
    rows: List<PickerRow<T>>,
    item: T?,
): Int = rows.indexOfFirst { it is PickerRow.Entry && it.item == item }.takeIf { it >= 0 } ?: firstEntry(rows)

/**
 * The index of the next entry after [from] in the direction of [step] (1 down, -1 up), skipping headers; [from] if
 * there is none, so the selection stops at the first and last entries. From -1 (nothing selected) down is the first.
 */
internal fun nextEntry(
    rows: List<PickerRow<*>>,
    from: Int,
    step: Int,
): Int {
    var index = from + step
    while (index in rows.indices && rows[index] is PickerRow.Header) index += step
    return if (index in rows.indices) index else from
}

/** The row to scroll to the top to show the entry at [index]: its header if that is right above it, else itself. */
internal fun scrollTarget(
    rows: List<PickerRow<*>>,
    index: Int,
): Int = if (index > 0 && rows[index - 1] is PickerRow.Header) index - 1 else index
