package dev.sluide.codeintelligence.symbols

import dev.sluide.codeintelligence.ui.SearchView

/** A [SearchView] that keeps what it shows; the test types and chooses. */
internal class FakeSearchView<T> : SearchView<T> {
    override var isShowing = false
    override var query = ""
    override var placeholder = ""
    override var onQuery: (String) -> Unit = {}
    override var onChoose: (T) -> Unit = {}
    override var onHidden: () -> Unit = {}
    var items: List<T> = emptyList()
    var selected: T? = null
    var note: String? = null

    /** The `selected` argument of the last [setItems], as the code under test passed it. */
    var requested: T? = null

    /** `false`: the popup has no place to show, as an editor without a text area. */
    var hasPlace = true

    override fun open(): Boolean {
        if (!hasPlace) return false
        if (!isShowing) query = ""
        isShowing = true
        return true
    }

    override fun setItems(
        items: List<T>,
        selected: T?,
    ) {
        requested = selected
        this.items = items
        this.selected = selected?.takeIf { it in items } ?: this.selected?.takeIf { it in items } ?: items.firstOrNull()
    }

    override fun note(text: String): Boolean {
        note = text
        return true
    }

    /** The user typed so that the field holds [text]. */
    fun type(text: String) {
        query = text
        onQuery(text)
    }

    /** The user took [item]: the popup hides first. */
    fun choose(item: T) {
        hide()
        onChoose(item)
    }

    override fun hide() {
        if (!isShowing) return
        isShowing = false
        onHidden()
    }

    override fun close() = hide()
}
