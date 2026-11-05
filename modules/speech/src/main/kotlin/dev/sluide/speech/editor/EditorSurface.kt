package dev.sluide.speech.editor

import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.StyledRange
import dev.sluide.speech.ui.ReadingSurface

/** The overlay layer of the reading's highlight. */
internal const val READ_ALOUD_LAYER = "read-aloud"

/** A [ReadingSurface] over a code [editor]: not editable means holding one of its locks. */
internal class EditorSurface(
    private val editor: ExtensibleCodeEditor,
) : ReadingSurface {
    private var lock: AutoCloseable? = null

    override val caretOffset get() = editor.caretOffset

    override val selection: IntRange?
        get() = editor.selection.takeIf { it.length > 0 }?.let { it.start until it.end }

    override var editable: Boolean
        get() = lock == null
        set(value) {
            if (value) {
                lock?.close()
                lock = null
            } else if (lock == null) {
                lock = editor.lock()
            }
        }

    override fun overlay(ranges: List<StyledRange>) = editor.overlay(READ_ALOUD_LAYER, ranges)

    override fun reveal(
        start: Int,
        end: Int,
    ) = editor.reveal(start, end)
}
