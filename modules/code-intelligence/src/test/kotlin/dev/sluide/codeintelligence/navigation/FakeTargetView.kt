package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.binding.SourcePosition

/** A [TargetView] that records what it shows. */
internal class FakeTargetView : TargetView {
    override var isShowing = false
    override var onChoose: (NavigationTarget) -> Unit = {}
    override var onHidden: () -> Unit = {}
    override var onSelected: (NavigationTarget?) -> Unit = {}
    var range: IntRange? = null
    var items: List<NavigationTarget> = emptyList()
    var placeholder: String? = null
    var note = false

    /** What [preview] was asked for, in order. */
    val previewed = mutableListOf<SourcePosition?>()

    override fun preview(position: SourcePosition?) {
        previewed += position
    }

    override fun show(
        range: IntRange,
        items: List<NavigationTarget>,
        placeholder: String,
        note: Boolean,
    ) {
        this.range = range
        this.items = items
        this.placeholder = placeholder
        this.note = note
        isShowing = true
        // Simpler than the popup, which tells only a changed item and `null` when a showing list empties: the first
        // item is told once it shows.
        items.firstOrNull()?.let(onSelected)
    }

    override fun update(items: List<NavigationTarget>) {
        this.items = items
    }

    override fun hide() {
        if (!isShowing) return
        isShowing = false
        onHidden()
    }

    override fun close() = hide()

    /** Selects the item at [index], as a key or a click does. */
    fun select(index: Int) = onSelected(items[index])

    /** Takes the item at [index], as Enter would. */
    fun choose(index: Int) {
        val target = items[index]
        hide()
        onChoose(target)
    }
}
