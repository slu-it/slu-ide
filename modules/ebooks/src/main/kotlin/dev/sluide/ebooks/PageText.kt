package dev.sluide.ebooks

/** What [PageText.text] decided to emit for a text node: the span's offset in the flat text and its exact content. */
internal class Emission(
    val offset: Int,
    val content: String,
)

/**
 * The flat text of a [BookPage] while it is built: whitespace is normalised, blocks are separated by a blank line
 * and `br` by a line break, but only in front of the next text (so never at the start, never doubled). Anchors wait
 * for the next text and then get its offset. Offsets are the contract between the page's spans and [BookPage.text].
 */
internal class PageText {
    private val flat = StringBuilder()
    private val skipRanges = mutableListOf<IntRange>()
    private val anchorOffsets = linkedMapOf<String, Int>()
    private val pendingAnchors = mutableListOf<String>()
    private var pendingBreak = ""
    private var pendingSpace = false
    private var skipDepth = 0
    private var skipFrom = -1
    private var skipTo = -1

    /** Number of open `pre` elements; their text is kept as written. */
    var preDepth = 0

    val length: Int get() = flat.length
    val text: String get() = flat.toString()
    val anchors: Map<String, Int> get() = anchorOffsets

    /** Sorted skip ranges, overlapping or touching ones merged. */
    val skip: List<IntRange>
        get() {
            val merged = mutableListOf<IntRange>()
            for (range in skipRanges.sortedBy { it.first }) {
                val last = merged.lastOrNull()
                if (last != null && range.first <= last.last + 1) {
                    merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
                } else {
                    merged += range
                }
            }
            return merged
        }

    fun block() {
        pendingBreak = "\n\n"
        pendingSpace = false
    }

    fun lineBreak() {
        if (pendingBreak.isEmpty()) pendingBreak = "\n"
        pendingSpace = false
    }

    /** [key] gets the offset of the first text at or after this point. */
    fun anchor(key: String) {
        pendingAnchors += key
    }

    /** Gives the anchors that still wait the current end of the text (end of a chapter or of the book). */
    fun resolveAnchorsAtEnd() = resolveAnchors(flat.length)

    fun beginSkip() {
        if (skipDepth++ == 0) {
            skipFrom = -1
            skipTo = -1
        }
    }

    fun endSkip() {
        if (--skipDepth == 0 && skipFrom >= 0) skipRanges += skipFrom until skipTo
    }

    /** The span to write for the text node [raw], or null when it adds nothing to the flat text. */
    fun text(raw: String): Emission? {
        if (preDepth > 0) {
            val kept = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
            return if (kept.isEmpty()) null else place(kept, leading = false, trailing = false)
        }
        // Whitespace runs become one space; a leading or trailing space is handed on to the neighbouring text.
        val words = StringBuilder()
        var space = false
        var leading = false
        for (c in raw) {
            if (c == '\u0000') continue
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C') {
                space = true
            } else {
                if (words.isEmpty()) {
                    leading = space
                } else if (space) {
                    words.append(' ')
                }
                space = false
                words.append(c)
            }
        }
        if (words.isEmpty()) {
            if (space) pendingSpace = true
            return null
        }
        return place(words.toString(), leading, trailing = space)
    }

    private fun place(
        content: String,
        leading: Boolean,
        trailing: Boolean,
    ): Emission {
        var space = leading || pendingSpace
        if (flat.isEmpty()) {
            space = false
        } else if (pendingBreak.isNotEmpty()) {
            flat.append(pendingBreak)
            space = false
        } else if (flat.last().isWhitespace()) {
            space = false
        }
        pendingBreak = ""
        val offset = flat.length
        val body = if (space) " $content" else content
        flat.append(body)
        pendingSpace = trailing
        resolveAnchors(offset)
        if (skipDepth > 0) {
            if (skipFrom < 0) skipFrom = offset
            skipTo = flat.length
        }
        return Emission(offset, body)
    }

    private fun resolveAnchors(offset: Int) {
        for (key in pendingAnchors) anchorOffsets.putIfAbsent(key, offset)
        pendingAnchors.clear()
    }
}
