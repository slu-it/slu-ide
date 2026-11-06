package dev.sluide.terminal

private val log = System.getLogger(Osc52Filter::class.java.name)

private const val ESC = '\u001b'
private const val BEL = '\u0007'
private const val ST = '\u009c'

/** What starts an OSC 52 sequence; once it is complete, the targets and the payload follow. */
private const val OSC_52_START = "$ESC]52;"

/** Room for the targets and their `;` next to the payload; more is dropped like an oversized payload. */
private const val MAX_TARGETS = 16

/**
 * Takes OSC 52 clipboard writes (`ESC ] 52 ; <targets> ; <base64>` ended by BEL, `ESC \` or ST) out of a program's
 * output before JediTerm sees it and gives their text ([clipboardText]) to [onCopy] (ADR 0133). Everything else passes
 * unchanged and in order. It filters in front of the emulator because JediTerm replays synchronized-output blocks
 * (`ESC [ ? 2026 h` ... `l`) through a fresh emulator of its own, so a hook in the emulator misses what is inside.
 *
 * The output arrives in reads of any size, so the filter keeps its state across [filter] calls: an ESC that may
 * start OSC 52 (up to [MAX_HELD] characters, `ESC ] 5 2`) is held back until the next characters decide, then emitted
 * in front of them, or not at all when it is OSC 52. A payload over [OSC_52_MAX_PAYLOAD] is read to its end and
 * dropped. An ESC inside a payload that does not end it (`ESC \`) aborts the sequence: nothing is copied, and the ESC
 * and what follows are processed as output again, so a sequence starting there still works. Logs only lengths, never
 * the text. Not thread-safe: one reader uses it.
 */
internal class Osc52Filter(
    private val onCopy: (String) -> Unit,
) {
    private enum class State { TEXT, START, PAYLOAD, PAYLOAD_ESC }

    private var state = State.TEXT
    private val held = CharArray(MAX_HELD)
    private var heldCount = 0
    private var arguments = StringBuilder()
    private var dropped = 0L
    private var ended = false

    /**
     * Filters `input[inputOffset, inputOffset + length)` into [output] from [outputOffset] and returns the number of
     * characters written, at most [length] + [MAX_HELD]. [output] may be [input] when [outputOffset] + [MAX_HELD] <=
     * [inputOffset]: writing then never overtakes reading.
     */
    fun filter(
        input: CharArray,
        inputOffset: Int,
        length: Int,
        output: CharArray,
        outputOffset: Int,
    ): Int {
        var out = outputOffset
        for (index in inputOffset until inputOffset + length) out = process(input[index], output, out)
        return out - outputOffset
    }

    /**
     * Ends the output: returns the held-back start of a sequence that the end left undecided; an unfinished payload
     * is dropped.
     */
    fun flush(): CharArray {
        val rest = if (state == State.START) held.copyOf(heldCount) else CharArray(0)
        if (state == State.PAYLOAD || state == State.PAYLOAD_ESC) {
            log.log(System.Logger.Level.DEBUG, "OSC 52 cut off by the end of the output after ${received()} characters")
        }
        state = State.TEXT
        heldCount = 0
        arguments = StringBuilder()
        return rest
    }

    /**
     * Reads filtered output from [source] (a connector's read, `-1` at the end) into `buf[offset, offset + length)` and
     * returns how much, at least one character until the end, then `-1`: JediTerm takes a read of 0 for the end. Reads
     * again when everything read was a clipboard write; at the end, returns the held-back characters first. [length]
     * must exceed [MAX_HELD], which leaves room in front for the characters held back from the previous read.
     */
    fun read(
        buf: CharArray,
        offset: Int,
        length: Int,
        source: (CharArray, Int, Int) -> Int,
    ): Int {
        require(length > MAX_HELD) { "Reads need room for more than $MAX_HELD characters, got $length" }
        while (!ended) {
            // A count of 0 only loops again: ProcessTtyConnector's InputStreamReader never returns 0 for a length > 0.
            val count = source(buf, offset + MAX_HELD, length - MAX_HELD)
            if (count < 0) {
                ended = true
                val rest = flush()
                rest.copyInto(buf, offset)
                return if (rest.isEmpty()) -1 else rest.size
            }
            val kept = filter(buf, offset + MAX_HELD, count, buf, offset)
            if (kept > 0) return kept
        }
        return -1
    }

    private fun process(
        ch: Char,
        output: CharArray,
        start: Int,
    ): Int {
        var out = start
        when (state) {
            State.TEXT -> {
                if (ch == ESC) {
                    held[0] = ch
                    heldCount = 1
                    state = State.START
                } else {
                    output[out++] = ch
                }
            }

            State.START -> {
                if (ch != OSC_52_START[heldCount]) {
                    // Not OSC 52: what was held back goes out, then this character as if nothing had been held.
                    held.copyInto(output, out, 0, heldCount)
                    out += heldCount
                    heldCount = 0
                    state = State.TEXT
                    out = process(ch, output, out)
                } else if (heldCount == MAX_HELD) {
                    heldCount = 0
                    arguments = StringBuilder()
                    dropped = 0
                    state = State.PAYLOAD
                } else {
                    held[heldCount++] = ch
                }
            }

            State.PAYLOAD -> {
                when (ch) {
                    BEL, ST -> finish()
                    ESC -> state = State.PAYLOAD_ESC
                    else -> if (arguments.length < OSC_52_MAX_PAYLOAD + MAX_TARGETS) arguments.append(ch) else dropped++
                }
            }

            State.PAYLOAD_ESC -> {
                if (ch == '\\') {
                    finish()
                } else {
                    log.log(System.Logger.Level.DEBUG, "OSC 52 aborted after ${received()} characters")
                    arguments = StringBuilder()
                    state = State.TEXT
                    out = process(ESC, output, out)
                    out = process(ch, output, out)
                }
            }
        }
        return out
    }

    private fun finish() {
        state = State.TEXT
        val text = if (dropped > 0) null else clipboardText(arguments.toString())
        when {
            dropped > 0 -> {
                log.log(
                    System.Logger.Level.INFO,
                    "OSC 52 with ${received()} characters over the limit ignored",
                )
            }

            text == null -> {
                log.log(System.Logger.Level.DEBUG, "OSC 52 with ${received()} characters ignored")
            }

            else -> {
                log.log(System.Logger.Level.INFO, "OSC 52 put ${text.length} characters on the clipboard")
            }
        }
        // A new builder: an old one would keep the memory of a large payload.
        arguments = StringBuilder()
        text?.let(onCopy)
    }

    private fun received() = arguments.length + dropped

    companion object {
        /** The most characters held back between reads: `ESC ] 5 2`, which may still become OSC 52. */
        const val MAX_HELD = OSC_52_START.length - 1
    }
}
