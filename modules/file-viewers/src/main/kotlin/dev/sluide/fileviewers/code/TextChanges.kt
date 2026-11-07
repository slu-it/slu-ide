package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.TextChange
import org.fxmisc.richtext.CodeArea

private val log = System.getLogger(TextChanges::class.java.name)

/**
 * The changes of [area]'s text as [TextChange]s, in order, on the FX thread, each in the text the one before left.
 * Changes made together (a multi-change such as Replace All, or undoing one) arrive after all of them are applied:
 * [first] gets them as one list (the overlay layers, which restyle the text as it is then), then each listener
 * [added][add] gets them one by one. Changes that change nothing are left out.
 */
internal class TextChanges(
    area: CodeArea,
    private val first: (List<TextChange>) -> Unit,
) {
    private val listeners = mutableListOf<(TextChange) -> Unit>()

    private val subscription =
        area.multiPlainChanges().subscribe { changes ->
            val changed =
                changes
                    .filter { it.removed != it.inserted }
                    .map { TextChange(it.position, it.removed, it.inserted) }
            if (changed.isNotEmpty()) tell(changed)
        }

    /** Calls [listener] with every change from now on, until the handle is closed (twice is harmless). */
    fun add(listener: (TextChange) -> Unit): AutoCloseable {
        // A wrapper of its own, so that the same listener added twice is removed once per handle.
        val added: (TextChange) -> Unit = { listener(it) }
        listeners += added
        return AutoCloseable { listeners.remove(added) }
    }

    /** Stops following the text. */
    fun dispose() {
        subscription.unsubscribe()
        listeners.clear()
    }

    private fun tell(changes: List<TextChange>) {
        first(changes)
        val listening = listeners.toList()
        for (change in changes) {
            for (listener in listening) {
                try {
                    listener(change)
                } catch (e: Exception) {
                    log.log(System.Logger.Level.ERROR, "A listener of a code editor's text changes failed", e)
                }
            }
        }
    }
}
