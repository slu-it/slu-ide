package dev.sluide.speech.ui

import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.TextEditor

/** What a reading needs from the editor that shows the text, without the toolkit: the tab's or a code editor. */
internal interface ReadingSurface {
    val caretOffset: Int

    /** The selected offsets (`start until end`); null when nothing is selected. */
    val selection: IntRange?

    /** Whether the user can change the text; false while it is read. */
    var editable: Boolean

    fun overlay(ranges: List<StyledRange>)

    fun reveal(
        start: Int,
        end: Int,
    )
}

/** A [ReadingSurface] over the tool tab's [TextEditor]. */
internal class TextEditorSurface(
    private val editor: TextEditor,
) : ReadingSurface {
    override val caretOffset get() = editor.caretOffset

    override val selection: IntRange?
        get() = editor.selection.takeIf { it.length > 0 }?.let { it.start until it.end }

    override var editable: Boolean
        get() = editor.editable
        set(value) {
            editor.editable = value
        }

    override fun overlay(ranges: List<StyledRange>) = editor.overlay(ranges)

    override fun reveal(
        start: Int,
        end: Int,
    ) = editor.reveal(start, end)
}
