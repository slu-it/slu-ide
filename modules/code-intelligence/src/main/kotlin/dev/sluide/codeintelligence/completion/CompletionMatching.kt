package dev.sluide.codeintelligence.completion

import org.eclipse.lsp4j.CompletionItem

/** The most items the completion popup shows; the server may send a thousand for one letter. */
internal const val MAX_SHOWN = 200

/**
 * Whether the code point [cp] belongs to an identifier for completion: a letter, a number, a combining mark or `_`
 * (`[\p{L}\p{N}\p{Mn}\p{Mc}_]`).
 */
internal fun isWordCodePoint(cp: Int): Boolean =
    cp == '_'.code ||
        Character.isLetter(cp) ||
        when (Character.getType(cp).toByte()) {
            Character.DECIMAL_DIGIT_NUMBER,
            Character.LETTER_NUMBER,
            Character.OTHER_NUMBER,
            Character.NON_SPACING_MARK,
            Character.COMBINING_SPACING_MARK,
            -> true

            else -> false
        }

/**
 * Whether the UTF-16 char [c] belongs to an identifier ([isWordCodePoint]); half of a surrogate pair counts, for the
 * letters beyond the BMP, where one char alone cannot tell.
 */
internal fun isWordChar(c: Char): Boolean = Character.isSurrogate(c) || isWordCodePoint(c.code)

/** The column where the word ending at [column] of [line] starts ([column] itself if no word char is before it). */
internal fun wordStart(
    line: String,
    column: Int,
): Int {
    var start = column.coerceIn(0, line.length)
    while (start > 0) {
        val cp = line.codePointBefore(start)
        if (!isWordCodePoint(cp)) break
        start -= Character.charCount(cp)
    }
    return start
}

/** The column where the word going on at [column] of [line] ends ([column] itself if no word char follows). */
internal fun wordEnd(
    line: String,
    column: Int,
): Int {
    var end = column.coerceIn(0, line.length)
    while (end < line.length) {
        val cp = line.codePointAt(end)
        if (!isWordCodePoint(cp)) break
        end += Character.charCount(cp)
    }
    return end
}

/** How well a completion item matches the typed prefix; lower is better. */
internal enum class MatchKind {
    /** The item starts with the prefix (ignoring case). */
    PREFIX,

    /** The prefix runs through starts of the item's parts: `gcw` in `get_cwd`, `gC` in `getCwd`, `cwd` in `get_cwd`. */
    BOUNDARY,

    /** The prefix's characters appear in the item in order. */
    SUBSEQUENCE,
}

/** How [prefix] matches [candidate], ignoring case; `null` if it does not. An empty prefix matches as a prefix. */
internal fun matchKind(
    prefix: String,
    candidate: String,
): MatchKind? =
    when {
        candidate.startsWith(prefix, ignoreCase = true) -> MatchKind.PREFIX
        BoundaryMatch(prefix, candidate).matches() -> MatchKind.BOUNDARY
        isSubsequence(prefix, candidate) -> MatchKind.SUBSEQUENCE
        else -> null
    }

/**
 * The completion items to show for [prefix] (the word chars before the caret), best first (ADR 0147): those whose
 * `filterText` (else `label`) matches it, prefix matches first, then part-start matches, then subsequences; within a
 * group items marked `preselect` first, then by `sortText` (else `label`). An item that looks and acts like an earlier
 * one (same label, label details, detail, inserted text and additional edits) is left out: ty sends the same name once
 * per module it can import it from, told apart by the label details and the import edit. At most [cap].
 */
internal fun rankCompletions(
    items: List<CompletionItem>,
    prefix: String,
    cap: Int = MAX_SHOWN,
): List<CompletionItem> {
    val matched =
        items.mapNotNull { item ->
            val label = item.label ?: return@mapNotNull null
            matchKind(prefix, item.filterText ?: label)?.let { kind -> kind to item }
        }
    val sorted =
        matched.sortedWith(
            compareBy<Pair<MatchKind, CompletionItem>> { it.first }
                .thenBy { if (it.second.preselect == true) 0 else 1 }
                .thenBy { it.second.sortText ?: it.second.label }
                .thenBy { it.second.label },
        )
    val seen = HashSet<List<Any?>>()
    return sorted
        .asSequence()
        .map { it.second }
        .filter { seen.add(sameness(it)) }
        .take(cap)
        .toList()
}

/** What an item looks and acts like: two items with the same sameness show and insert the same. */
private fun sameness(item: CompletionItem): List<Any?> {
    val textEdit = item.textEdit
    val inserted =
        when {
            textEdit == null -> item.insertText ?: item.label
            textEdit.isLeft -> textEdit.left.newText
            else -> textEdit.right.newText
        }
    return listOf(
        item.label,
        item.labelDetails?.detail,
        item.labelDetails?.description,
        item.detail,
        inserted,
        item.additionalTextEdits,
    )
}

private fun isSubsequence(
    prefix: String,
    candidate: String,
): Boolean {
    var at = 0
    for (c in prefix) {
        at = indexOfIgnoringCase(candidate, c, at)
        if (at < 0) return false
        at++
    }
    return true
}

/**
 * Whether [prefix] matches [candidate] as a run of part prefixes, starting at any part start: each next prefix
 * character either continues the current part or starts at a later part start (see [isPartStart]). Remembers each
 * (prefix index, candidate index) it decided, so a name with many parts costs at most prefix × candidate steps.
 */
private class BoundaryMatch(
    private val prefix: String,
    private val candidate: String,
) {
    private val decided = arrayOfNulls<Boolean>((prefix.length + 1) * (candidate.length + 1))

    /** The number of (prefix index, candidate index) pairs decided; for tests of the cost. */
    var steps = 0
        private set

    fun matches(): Boolean = candidate.indices.any { isPartStart(candidate, it) && from(0, it) }

    /** Whether the prefix from [p] matches from the part start [c], with [p] starting that part. */
    private fun from(
        p: Int,
        c: Int,
    ): Boolean {
        if (p == prefix.length) return true
        if (c >= candidate.length) return false
        val key = p * (candidate.length + 1) + c
        decided[key]?.let { return it }
        steps++
        val result = decide(p, c)
        decided[key] = result
        return result
    }

    private fun decide(
        p: Int,
        c: Int,
    ): Boolean {
        if (!isPartStart(candidate, c) || !sameChar(prefix[p], candidate[c])) return false
        // Take as much of this part as matches, then go on at a later part start with the rest.
        var run = 1
        while (p + run < prefix.length && c + run < candidate.length && !isPartStart(candidate, c + run) &&
            sameChar(prefix[p + run], candidate[c + run])
        ) {
            run++
        }
        for (taken in run downTo 1) {
            if (p + taken == prefix.length) return true
            for (next in c + taken until candidate.length) {
                if (isPartStart(candidate, next) && from(p + taken, next)) return true
            }
        }
        return false
    }
}

/** The number of steps [matchKind]'s part-start match takes for [prefix] in [candidate]; for tests of the cost. */
internal fun boundaryMatchSteps(
    prefix: String,
    candidate: String,
): Int = BoundaryMatch(prefix, candidate).also { it.matches() }.steps

/** Whether a part of an identifier starts at [index]: its start, after `_`, or an upper case letter after a lower one. */
private fun isPartStart(
    candidate: String,
    index: Int,
): Boolean {
    if (index == 0) return true
    val before = candidate[index - 1]
    val at = candidate[index]
    if (at == '_') return false
    return before == '_' || (at.isUpperCase() && !before.isUpperCase()) || (!isWordChar(before) && isWordChar(at))
}

private fun sameChar(
    a: Char,
    b: Char,
) = a.equals(b, ignoreCase = true)

private fun indexOfIgnoringCase(
    text: String,
    c: Char,
    from: Int,
): Int {
    for (i in from until text.length) if (sameChar(text[i], c)) return i
    return -1
}
