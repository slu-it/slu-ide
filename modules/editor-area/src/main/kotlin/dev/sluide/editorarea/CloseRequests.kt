package dev.sluide.editorarea

import javafx.util.Subscription

/** Calls [onRequest] for a watched tab when its [EditorTab.closeRequested] is, or turns, true. */
internal class CloseRequests(
    private val onRequest: (EditorTab) -> Unit,
) {
    private val watched = HashMap<EditorTab, Subscription>()

    /** Starts watching [tab]; a tab whose value is true already is reported at once, so call this after adding it. */
    fun watch(tab: EditorTab) {
        // A listener on the shared default would keep every tab, and its window, alive.
        if (tab.closeRequested === NEVER_CLOSE_REQUESTED) return
        watched[tab] = tab.closeRequested.subscribe { requested -> if (requested) onRequest(tab) }
    }

    /** Stops watching [tab], if it is watched. */
    fun unwatch(tab: EditorTab) {
        watched.remove(tab)?.unsubscribe()
    }
}
