package dev.sluide.speech.text

/**
 * What is spoken and where each spoken char came from in the shown text: `sourceOffsets[i]` is the offset in the
 * shown text of `text[i]`. A `"\n"` in [text] is a hard break (paragraph, heading, list item, table row) that ends a
 * sentence; all other whitespace is a single space.
 */
class SpokenText(
    val text: String,
    private val sourceOffsets: IntArray,
) {
    init {
        require(sourceOffsets.size == text.length) {
            "${sourceOffsets.size} source offsets for ${text.length} spoken chars"
        }
    }

    /**
     * The offset in the shown text of the spoken char at [spokenOffset]; [spokenOffset] equal to the text's length
     * maps to the end of the last source char (its offset + 1, or 0 when nothing is spoken).
     */
    fun sourceOffset(spokenOffset: Int): Int {
        require(spokenOffset in 0..text.length) { "Spoken offset $spokenOffset outside 0..${text.length}" }
        return when {
            spokenOffset < text.length -> sourceOffsets[spokenOffset]
            text.isEmpty() -> 0
            else -> sourceOffsets[text.length - 1] + 1
        }
    }

    /**
     * The exclusive end in the shown text of a spoken range ending (exclusive) at [spokenEnd]: the end of its last
     * source char. An empty range at 0 ends where it starts, at `sourceOffset(0)`.
     */
    fun sourceEnd(spokenEnd: Int): Int {
        require(spokenEnd in 0..text.length) { "Spoken end $spokenEnd outside 0..${text.length}" }
        return if (spokenEnd == 0) sourceOffset(0) else sourceOffset(spokenEnd - 1) + 1
    }

    /**
     * The first spoken offset whose source offset is at or after [sourceOffset], or the text's length when there is
     * none; for a caret or a click in the shown text. Source offsets never decrease along the spoken text.
     */
    fun spokenOffset(sourceOffset: Int): Int {
        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high) ushr 1
            if (sourceOffsets[middle] < sourceOffset) low = middle + 1 else high = middle
        }
        return low
    }
}

/**
 * Builds a [SpokenText] char by char with each char's source offset. Whitespace collapses: a run of spaces and line
 * breaks becomes one space, a [paragraph] break one `"\n"` (the stronger one of a run wins); both are dropped at the
 * start and the end.
 */
internal class SpokenTextBuilder {
    private val text = StringBuilder()
    private var offsets = IntArray(INITIAL_CAPACITY)
    private var pending = NONE
    private var pendingSource = 0

    /** Appends [char] from [source]; whitespace becomes a pending space. */
    fun char(
        char: Char,
        source: Int,
    ) {
        if (char.isWhitespace()) {
            space(source)
            return
        }
        if (pending != NONE && text.isNotEmpty()) append(if (pending == BREAK) '\n' else ' ', pendingSource)
        pending = NONE
        append(char, source)
    }

    /** A space at [source] before the next char. */
    fun space(source: Int) = raise(SPACE, source)

    /** A hard break (`"\n"`, ends a sentence) at [source] before the next char. */
    fun paragraph(source: Int) = raise(BREAK, source)

    fun build(): SpokenText = SpokenText(text.toString(), offsets.copyOf(text.length))

    private fun raise(
        level: Int,
        source: Int,
    ) {
        if (pending == NONE) pendingSource = source
        if (level > pending) pending = level
    }

    private fun append(
        char: Char,
        source: Int,
    ) {
        if (text.length == offsets.size) offsets = offsets.copyOf(offsets.size * 2)
        offsets[text.length] = source
        text.append(char)
    }

    private companion object {
        const val INITIAL_CAPACITY = 256
        const val NONE = 0
        const val SPACE = 1
        const val BREAK = 2
    }
}
