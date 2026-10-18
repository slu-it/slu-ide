package dev.sluide.fileviewers.code

import org.fxmisc.richtext.model.StyleSpan
import org.fxmisc.richtext.model.StyleSpans

/**
 * The characters whose style differs between [old] and [new], two styles of the same text: from the first differing
 * character to the last. `null` if both style every character the same; spans may be split differently.
 */
internal fun <S> changedSpanRange(
    old: StyleSpans<S>,
    new: StyleSpans<S>,
): IntRange? {
    val length = maxOf(old.length(), new.length())
    val start = firstDifference(old.toList(), new.toList(), length) ?: return null
    val fromEnd = checkNotNull(firstDifference(old.reversed(), new.reversed(), length))
    return start until length - fromEnd
}

/** How many characters [old] and [new] style the same from their start; `null` if all [length] of them. */
private fun <S> firstDifference(
    old: List<StyleSpan<S>>,
    new: List<StyleSpan<S>>,
    length: Int,
): Int? {
    val olds = old.filter { it.length > 0 }
    val news = new.filter { it.length > 0 }
    var i = 0
    var j = 0
    // Where the current spans of old and new end, and how far both are the same.
    var oldEnd = olds.firstOrNull()?.length ?: 0
    var newEnd = news.firstOrNull()?.length ?: 0
    var same = 0
    while (i < olds.size && j < news.size) {
        if (olds[i].style != news[j].style) return same
        same = minOf(oldEnd, newEnd)
        if (oldEnd == same && ++i < olds.size) oldEnd += olds[i].length
        if (newEnd == same && ++j < news.size) newEnd += news[j].length
    }
    return if (same >= length) null else same
}
