package dev.sluide.fileviewers

import java.io.IOException

/**
 * A file shown side by side with an original version of it, the differences coloured (ADR 0015); or a given text of a
 * file, e.g. as a commit left it (ADR 0038).
 */
interface DiffFileView : FileView {
    /** Reads the file again (a given text stays as it is) and shows it against [diff], keeping the scroll position. */
    @Throws(IOException::class)
    fun show(diff: TextDiff)

    /**
     * Goes back to where the diff was opened from, e.g. the file's editor; offered in the view (a button, Escape) while
     * set. Set by whoever shows the view.
     */
    var onBack: (() -> Unit)?
        get() = null
        set(_) {}

    /**
     * Swaps the two sides, e.g. of a comparison of two files; offered as a button in the view while set. Set by
     * whoever shows the view.
     */
    var onSwap: (() -> Unit)?
        get() = null
        set(_) {}

    /**
     * Jumps to the change at [line] of the file (0-based), e.g. the one a change marker on that line stands for: the
     * change the line is in, else lines removed right above it, else the next change, else the last one.
     */
    fun jumpToChangeAt(line: Int) {}
}
