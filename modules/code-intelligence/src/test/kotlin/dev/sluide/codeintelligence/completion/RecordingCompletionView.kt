package dev.sluide.codeintelligence.completion

import org.eclipse.lsp4j.CompletionItem

/** A completion popup that records what it was asked to show; [canShow] `false` is a popup that cannot show. */
internal class RecordingCompletionView : CompletionView {
    lateinit var model: CompletionModel
    val shown = mutableListOf<List<String>>()
    var showing = false
    var refreshes = 0
    var hides = 0
    var canShow = true

    override fun show(
        wordStart: Int,
        items: List<CompletionItem>,
    ): Boolean {
        if (!canShow) return false
        shown += items.map { it.label }
        showing = true
        return true
    }

    override fun hide() {
        hides++
        if (!showing) return
        showing = false
        model.viewHidden()
    }

    override fun refreshDetail() {
        refreshes++
    }

    override val isShowing: Boolean get() = showing
}
