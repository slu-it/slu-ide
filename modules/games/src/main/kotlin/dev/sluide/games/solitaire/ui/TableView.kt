package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.Table
import javafx.animation.PauseTransition
import javafx.application.ColorScheme
import javafx.scene.image.ImageView
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.util.Duration

/**
 * The Solitaire table: slots, cards and highlights, drawn from [layout]. Transparent: the felt is on the tab's root.
 * The view never changes the game; it asks [host] to play and waits for the owner's [show] with the new table.
 * Mouse play is in [TableDrag]. Put it in a ScrollPane with fitToWidth and fitToHeight: its preferred size is the
 * minimum the layout needs.
 *
 * Everything is recreated on [show], on a size change and when the theme or the screen scale changes (correctness
 * over reuse; a table shows at most about a hundred cards).
 */
internal class TableView(
    private val host: TableHost,
    slideBack: Duration = Duration.millis(150.0),
    hintDuration: Duration = Duration.millis(1500.0),
) : Pane() {
    /** No mouse input while false. */
    var inputEnabled: Boolean = true

    internal var shown: Table = host.table
        private set
    private var current: Layout = layout(shown, 0.0, 0.0)
    private var images: CardImages? = null
    private val nodes = HashMap<Pair<PileId, Int>, ImageView>()
    private val highlights = mutableListOf<Region>()
    private var laidOut = 0.0 to 0.0
    private var cursor: Pair<PileId, Int>? = null
    private var hint: Triple<PileId, Int, PileId>? = null
    private val hintTimer =
        PauseTransition(hintDuration).apply {
            setOnFinished {
                hint = null
                refreshHighlights()
            }
        }
    private val drag = TableDrag(this, host, slideBack)

    init {
        styleClass.add("solitaire-table")
        stylesheets.add(
            checkNotNull(TableView::class.java.getResource("/dev/sluide/games/solitaire/solitaire.css"))
                .toExternalForm(),
        )
        drag.install()
        redraw()
        // the subscriptions call back at once with no scene; the first drawing is the one above
        sceneProperty().flatMap { it.preferences.colorSchemeProperty() }.subscribe { _ -> redrawInScene() }
        sceneProperty()
            .flatMap { it.windowProperty() }
            .flatMap<Number> { it.outputScaleXProperty() }
            .subscribe { _ -> redrawInScene() }
    }

    /** Shows [table]: relayout and redraw; ends a drag and a hint. */
    fun show(table: Table) {
        shown = table
        hint = null
        hintTimer.stop()
        redraw()
    }

    /** The layout in use. */
    fun layoutNow(): Layout = current

    /** Highlights the top [depth] cards of [pile], or its slot when it has none; null removes it. */
    fun showCursor(
        pile: PileId?,
        depth: Int,
    ) {
        cursor = pile?.let { it to depth }
        refreshHighlights()
    }

    /** Outlines the top [count] cards of [from] and the target [to]; removed after the hint delay or by [show]. */
    fun showHint(
        from: PileId,
        count: Int,
        to: PileId,
    ) {
        hint = Triple(from, count, to)
        hintTimer.playFromStart()
        refreshHighlights()
    }

    override fun layoutChildren() {
        if (laidOut != width to height) redraw()
    }

    override fun computePrefWidth(height: Double): Double = layout(shown, 0.0, 0.0).prefWidth

    override fun computePrefHeight(width: Double): Double = layout(shown, if (width < 0) 0.0 else width, 0.0).prefHeight

    override fun computeMinWidth(height: Double): Double = computePrefWidth(height)

    override fun computeMinHeight(width: Double): Double = computePrefHeight(width)

    /** The nodes of the top [count] cards of [pile], bottom to top. */
    internal fun nodesOf(
        pile: PileId,
        count: Int,
    ): List<ImageView> =
        current.cards
            .filter { it.pile == pile }
            .takeLast(count)
            .mapNotNull { nodes[it.pile to it.index] }

    /** The area of the top [count] cards of [pile] (its slot if it has none shown). */
    internal fun areaOf(
        pile: PileId,
        count: Int,
    ): Rect? {
        val slot = current.slots[pile] ?: return null
        return current.cards
            .filter { it.pile == pile }
            .takeLast(count)
            .fold(null as Rect?) { acc, placed -> acc?.union(placed.rect) ?: placed.rect } ?: slot
    }

    internal fun addOverlay(
        rect: Rect,
        styleClass: String,
    ): Region =
        Region()
            .apply {
                this.styleClass.add(styleClass)
                isMouseTransparent = true
                isManaged = false
                resizeRelocate(rect.x, rect.y, rect.w, rect.h)
            }.also { children.add(it) }

    private fun redrawInScene() {
        if (scene != null) redraw()
    }

    /** Swaps the cursor and hint outlines only; cards and a drag in progress stay. */
    private fun refreshHighlights() {
        children.removeAll(highlights)
        highlights.clear()
        cursor?.let { (pile, depth) -> areaOf(pile, depth)?.let { highlights += addOverlay(it, "sol-cursor") } }
        hint?.let { (from, count, to) ->
            areaOf(from, count)?.let { highlights += addOverlay(it, "sol-hint") }
            areaOf(to, 1)?.let { highlights += addOverlay(it, "sol-hint") }
        }
        drag.raise()
    }

    private fun redraw() {
        drag.cancel()
        laidOut = width to height
        current = layout(shown, width, height)
        val cards = imagesFor(current.cardWidth)
        children.clear()
        nodes.clear()
        for (slot in current.slots.values) {
            addOverlay(slot, "sol-slot")
        }
        for (placed in current.cards) {
            val view =
                ImageView(if (placed.faceUp) cards.face(placed.card) else cards.back()).apply {
                    fitWidth = placed.rect.w
                    fitHeight = placed.rect.h
                    isManaged = false
                    relocate(placed.rect.x, placed.rect.y)
                }
            nodes[placed.pile to placed.index] = view
            children.add(view)
        }
        highlights.clear()
        refreshHighlights()
    }

    private fun imagesFor(cardWidth: Double): CardImages {
        val dark = scene?.preferences?.colorScheme == ColorScheme.DARK
        val scale = scene?.window?.outputScaleX ?: 1.0
        val old = images
        if (old != null && old.width == cardWidth && old.dark == dark && old.scale == scale) return old
        return CardImages(cardWidth, dark, scale).also { images = it }
    }
}
