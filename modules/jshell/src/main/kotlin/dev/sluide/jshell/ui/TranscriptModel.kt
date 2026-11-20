package dev.sluide.jshell.ui

import dev.sluide.jshell.EvalEvent

internal enum class ParagraphKind { HEADER, OUTPUT, ERROR_OUTPUT, VALUE, DIAGNOSTIC, THROWN, NOTE }

internal data class TranscriptParagraph(
    val text: String,
    val kind: ParagraphKind,
)

/**
 * What a call changed, in the order the pane applies it: [append] is text for the end of the paragraph that was last
 * before the call (a print without a newline continuing), then the [added] paragraphs follow, then the first [dropped]
 * paragraphs are removed (the cap). [dropped] never counts paragraphs that were added by the same call.
 */
internal data class TranscriptChange(
    val append: TranscriptParagraph?,
    val added: List<TranscriptParagraph>,
    val dropped: Int,
)

/**
 * One change that does what [changes], applied one after the other, do to a pane that shows [shown] paragraphs: the
 * texts appended to the last paragraph or to the last added one are joined, the drops add up, and drops that reach
 * paragraphs the changes added leave those out instead, so that the result's [TranscriptChange.dropped] is at most
 * [shown].
 */
internal fun mergeChanges(
    changes: List<TranscriptChange>,
    shown: Int,
): TranscriptChange {
    class Segment(
        val kind: ParagraphKind,
        val text: StringBuilder,
    )
    var append: Segment? = null
    val added = ArrayList<Segment>()
    var dropped = 0
    for (change in changes) {
        change.append?.let { extra ->
            val target = added.lastOrNull() ?: append ?: Segment(extra.kind, StringBuilder()).also { append = it }
            target.text.append(extra.text)
        }
        change.added.mapTo(added) { Segment(it.kind, StringBuilder(it.text)) }
        dropped += change.dropped
    }
    val skipped = (dropped - shown).coerceAtLeast(0)
    return TranscriptChange(
        append?.let { TranscriptParagraph(it.text.toString(), it.kind) },
        added.subList(skipped, added.size).map { TranscriptParagraph(it.text.toString(), it.kind) },
        dropped - skipped,
    )
}

/**
 * The paragraphs of the transcript, FX-free. A paragraph counts its characters plus one for its line end towards
 * [capChars]; over it the oldest paragraphs are dropped. One output paragraph longer than a quarter of the cap is cut
 * with a "… output cut" note, and the rest of that snippet's output (and late output) is dropped until the next
 * snippet or evaluation starts; other long paragraphs end in an ellipsis. Not thread-safe.
 */
internal class TranscriptModel(
    private val capChars: Int = 1_048_576,
) {
    private class Entry(
        val kind: ParagraphKind,
        val jump: Int?,
        val text: StringBuilder,
    )

    private val entries = ArrayDeque<Entry>()
    private var total = 0L
    private var document = ""

    /** The last paragraph while it is an output paragraph that may continue, and the stream it prints to. */
    private var open: Entry? = null

    /**
     * The streams whose last output ended in a carriage return: a line feed first in a stream's next output belongs to
     * it, also after the other stream's output between.
     */
    private val carriageReturnOf = HashSet<ParagraphKind>()

    /** The snippet's output was cut: more of it is dropped. */
    private var cut = false

    private val limit = capChars / 4

    init {
        require(capChars >= 4) { "cap $capChars" }
    }

    /** A copy of the paragraphs, oldest first. */
    val paragraphs: List<TranscriptParagraph> get() = entries.map { TranscriptParagraph(it.text.toString(), it.kind) }

    /** Begins an evaluation of [text] (the whole document as evaluated); headers are taken from it. */
    fun startEvaluation(text: String) {
        document = text
        cut = false
    }

    fun accept(event: EvalEvent): TranscriptChange =
        when (event) {
            is EvalEvent.SnippetStarted -> {
                cut = false
                change { add(ParagraphKind.HEADER, header(event.start), event.line) }
            }

            is EvalEvent.Output -> {
                output(event.text, event.error)
            }

            is EvalEvent.Value -> {
                lines(ParagraphKind.VALUE, event.feedback, null)
            }

            is EvalEvent.Diagnostic -> {
                diagnostic(event)
            }

            is EvalEvent.Thrown -> {
                lines(ParagraphKind.THROWN, event.text, event.line)
            }

            EvalEvent.Stopped -> {
                note("Stopped")
            }

            EvalEvent.Finished -> {
                NONE
            }
        }

    fun lateOutput(
        text: String,
        error: Boolean,
    ): TranscriptChange = output(text, error)

    fun note(text: String): TranscriptChange = lines(ParagraphKind.NOTE, text, null)

    fun clear(): TranscriptChange {
        val count = entries.size
        entries.clear()
        total = 0
        open = null
        return if (count == 0) NONE else TranscriptChange(null, emptyList(), count)
    }

    /** The 0-based document line to jump to from paragraph [index], or null. */
    fun jumpLine(index: Int): Int? = entries.getOrNull(index)?.jump

    private fun header(start: Int): String {
        val from = start.coerceIn(0, document.length)
        var end = from
        while (end < document.length && document[end] != '\n' && document[end] != '\r') end++
        val first = document.substring(from, end).trimEnd()
        return "jshell> " + if (first.length > HEADER_CHARS) clip(first, HEADER_CHARS) + "…" else first
    }

    private fun diagnostic(event: EvalEvent.Diagnostic): TranscriptChange {
        val prefix = "line ${event.line + 1}: "
        return change {
            splitLines(event.message).forEachIndexed { i, line ->
                add(ParagraphKind.DIAGNOSTIC, clipped(if (i == 0) prefix + line else line), event.line)
            }
            if (event.message.isEmpty()) add(ParagraphKind.DIAGNOSTIC, prefix.trimEnd(), event.line)
        }
    }

    private fun lines(
        kind: ParagraphKind,
        text: String,
        jump: Int?,
    ): TranscriptChange = change { splitLines(text).forEach { add(kind, clipped(it), jump) } }

    /** The lines of [text], none for an empty text and no empty last one for a trailing line end. */
    private fun splitLines(text: String): List<String> {
        val parts = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return if (parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    private fun clipped(text: String) = if (text.length > limit) clip(text, limit) + "…" else text

    /** The first [length] characters of [text], never ending inside a surrogate pair. */
    private fun clip(
        text: String,
        length: Int,
    ) = if (length > 0 && text[length - 1].isHighSurrogate()) {
        text.take(length - 1)
    } else {
        text.take(length)
    }

    private fun output(
        text: String,
        error: Boolean,
    ): TranscriptChange {
        if (text.isEmpty()) return NONE
        val kind = if (error) ParagraphKind.ERROR_OUTPUT else ParagraphKind.OUTPUT
        return change {
            var from = 0
            if (kind in carriageReturnOf && text[0] == '\n') from = 1
            if (text.last() == '\r') carriageReturnOf += kind else carriageReturnOf -= kind
            while (from <= text.length && !cut) {
                var end = from
                while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
                val terminated = end < text.length
                val piece = text.substring(from, end)
                val current = open
                if (current != null && current.kind == kind) {
                    continueOpen(current, piece, terminated)
                } else {
                    newParagraph(kind, piece, terminated)
                }
                if (!terminated) break
                from = if (text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n') end + 2 else end + 1
            }
        }
    }

    private fun Collector.continueOpen(
        entry: Entry,
        piece: String,
        terminated: Boolean,
    ) {
        val room = limit - entry.text.length
        if (piece.length > room) {
            val part = clip(piece, room.coerceAtLeast(0))
            grow(entry, part)
            cutOff()
        } else {
            grow(entry, piece)
            if (terminated) open = null
        }
    }

    private fun Collector.newParagraph(
        kind: ParagraphKind,
        piece: String,
        terminated: Boolean,
    ) {
        if (piece.isEmpty() && !terminated) {
            open = null
            return
        }
        if (piece.length > limit) {
            add(kind, clip(piece, limit), null)
            open = null
            cutOff()
            return
        }
        add(kind, piece, null)
        open = if (terminated) null else entries.last()
    }

    private fun Collector.cutOff() {
        open = null
        cut = true
        add(ParagraphKind.NOTE, "… output cut", null)
    }

    private fun Collector.grow(
        entry: Entry,
        part: String,
    ) {
        if (part.isEmpty()) return
        entry.text.append(part)
        total += part.length
        appended = TranscriptParagraph(part, entry.kind)
    }

    private inner class Collector {
        var appended: TranscriptParagraph? = null
        val added = ArrayList<TranscriptParagraph>()
        val oldSize = entries.size

        fun add(
            kind: ParagraphKind,
            text: String,
            jump: Int?,
        ) {
            if (kind != ParagraphKind.OUTPUT && kind != ParagraphKind.ERROR_OUTPUT) {
                open = null
                carriageReturnOf.clear()
            }
            entries.addLast(Entry(kind, jump, StringBuilder(text)))
            total += text.length + 1
            added += TranscriptParagraph(text, kind)
        }
    }

    private fun change(block: Collector.() -> Unit): TranscriptChange {
        val collector = Collector()
        collector.block()
        var dropped = 0
        while (total > capChars) {
            val oldest = entries.removeFirst()
            total -= oldest.text.length + 1
            dropped++
        }
        val skipped = (dropped - collector.oldSize).coerceAtLeast(0)
        return TranscriptChange(collector.appended, collector.added.drop(skipped), dropped - skipped)
    }

    private companion object {
        const val HEADER_CHARS = 200
        val NONE = TranscriptChange(null, emptyList(), 0)
    }
}
