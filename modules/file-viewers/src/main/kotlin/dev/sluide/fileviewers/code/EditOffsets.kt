package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.TextChange

/**
 * [range] after [change]: unchanged entirely before the changed range, moved by the change in length entirely after
 * it, `null` (dropped) when it overlaps or touches it (ADR 0141's following overlay layers).
 */
internal fun followRange(
    range: StyledRange,
    change: TextChange,
): StyledRange? {
    val start = change.start
    val end = start + change.removed.length
    val delta = change.inserted.length - change.removed.length
    return when {
        range.end < start -> range
        range.start > end -> StyledRange(range.start + delta, range.end + delta, range.styleClass)
        else -> null
    }
}

/**
 * Where [offset] of the text before [change] is after it, for restyling: unchanged before the changed range, moved by
 * the change in length after it, and the change's start inside it.
 */
internal fun offsetAfter(
    offset: Int,
    change: TextChange,
): Int {
    val end = change.start + change.removed.length
    return when {
        offset <= change.start -> offset
        offset >= end -> offset + change.inserted.length - change.removed.length
        else -> change.start
    }
}

/** The [ranges] of an overlay layer, and whether they follow edits rather than being cleared by them. */
internal data class LayerRanges(
    val ranges: List<StyledRange>,
    val following: Boolean,
)

/**
 * Overlay [layers] after [changes]: what is left of them and what of the text to restyle ([restyle], `null` for
 * nothing), in the text after all changes.
 */
internal data class FollowedLayers<K>(
    val layers: Map<K, LayerRanges>,
    val restyle: IntRange?,
)

/**
 * [layers] after [changes], made together (one change, or a multi-change such as Replace All, each in the text the one
 * before left): layers that do not follow edits lose every range, the others keep the ranges [followRange] keeps;
 * emptied layers go, the others keep their order. The text to restyle, carried through the later changes, covers
 * where the classes of dropped ranges went with the text and the inserted texts, which may have taken on the classes
 * before them.
 */
internal fun <K> followChanges(
    layers: Map<K, LayerRanges>,
    changes: List<TextChange>,
): FollowedLayers<K> {
    var current = layers
    var from = Int.MAX_VALUE
    var to = Int.MIN_VALUE
    for (change in changes) {
        if (from <= to) {
            from = offsetAfter(from, change)
            to = offsetAfter(to, change)
        }
        from = minOf(from, change.start)
        to = maxOf(to, change.start + change.inserted.length)
        val next = LinkedHashMap<K, LayerRanges>()
        for ((key, layer) in current) {
            val kept = mutableListOf<StyledRange>()
            for (range in layer.ranges) {
                val moved = if (layer.following) followRange(range, change) else null
                if (moved != null) {
                    kept += moved
                } else {
                    from = minOf(from, offsetAfter(range.start, change))
                    to = maxOf(to, offsetAfter(range.end, change))
                }
            }
            if (kept.isNotEmpty()) next[key] = LayerRanges(kept, layer.following)
        }
        current = next
    }
    return FollowedLayers(current, if (from < to) from until to else null)
}

/**
 * [edits] sorted by their range, inserts at the same offset in the order given, so that applying them from the last
 * keeps every offset valid. Throws [IllegalArgumentException] for an edit outside a text of [length] or reversed, and
 * for overlapping edits.
 */
internal fun sortedEdits(
    edits: List<OffsetEdit>,
    length: Int,
): List<OffsetEdit> {
    for (edit in edits) {
        require(edit.start in 0..edit.end && edit.end <= length) { "$edit is outside the text of length $length" }
    }
    val sorted = edits.sortedWith(compareBy({ it.start }, { it.end }))
    sorted.zipWithNext { previous, next -> require(previous.end <= next.start) { "$previous overlaps $next" } }
    return sorted
}

/**
 * Where [offset] (a caret or selection end) is after applying [sorted] ([sortedEdits]): an edit ending at or before it
 * shifts it by its change in length, an edit containing it puts it after the edit's new text, later edits leave it.
 */
internal fun offsetAfterEdits(
    offset: Int,
    sorted: List<OffsetEdit>,
): Int {
    var shift = 0
    for (edit in sorted) {
        when {
            edit.end <= offset -> shift += edit.text.length - (edit.end - edit.start)
            edit.start < offset -> return edit.start + shift + edit.text.length
            else -> break
        }
    }
    return offset + shift
}
