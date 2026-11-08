package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.binding.SourcePosition

/**
 * The list of places of one bound editor, shared by go to definition and find usages (ADR 0146): a note, a list
 * waiting for its answer, or the places to choose from, whose line texts come from the editor for its own file and
 * from disk off the FX thread for others ([LineReader]). Taking one jumps there ([Navigation.jump]). FX thread.
 */
internal class TargetChooser(
    private val binding: DocumentBinding,
    private val navigation: Navigation,
    private val view: TargetView,
    private val lines: LineReader,
) : AutoCloseable {
    /** Told when the list hid, by any cause: the request on its way is not wanted any more. */
    var onHidden: () -> Unit = {}

    private var reading: AutoCloseable? = null

    init {
        view.onChoose = { target -> navigation.jump(binding, target.position) }
        view.onHidden = {
            stopReading()
            onHidden()
        }
    }

    /** Whether the list or a note shows. */
    val isShowing: Boolean get() = view.isShowing

    /** Shows [text] at [range] until the next key or click. */
    fun note(
        range: IntRange,
        text: String,
    ) {
        stopReading()
        view.show(range, emptyList(), text, note = true)
    }

    /** Shows an empty list saying [text] at [range] while the answer is on its way. */
    fun waiting(
        range: IntRange,
        text: String,
    ) {
        stopReading()
        view.show(range, emptyList(), text)
    }

    /** Offers [positions] at [range], in this order; their line texts follow as they are read. */
    fun choose(
        range: IntRange,
        positions: List<SourcePosition>,
    ) {
        stopReading()
        val targets = positions.map { NavigationTarget(it, ownLine(it)) }
        view.show(range, targets, "No results")
        val unread = targets.filter { it.lineText == null }.map { it.position }
        if (unread.isEmpty()) return
        reading =
            lines.read(unread) { texts ->
                reading = null
                if (view.isShowing) view.update(targets.map { it.copy(lineText = it.lineText ?: texts[it.position]) })
            }
    }

    fun hide() = view.hide()

    override fun close() {
        stopReading()
        view.close()
    }

    /** The trimmed text of [position]'s line if it is in the editor's own file (unsaved changes included). */
    private fun ownLine(position: SourcePosition): String? {
        if (!sameFile(position.file, binding.file) || position.line !in 0 until binding.editor.lineCount) return null
        return binding.editor.lineText(position.line).trim()
    }

    private fun stopReading() {
        reading?.close()
        reading = null
    }
}
