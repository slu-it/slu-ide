package dev.sluide.games.doubles.ui

import dev.sluide.games.doubles.Cell
import dev.sluide.games.doubles.GAP_FACTOR
import dev.sluide.games.doubles.MIN_TILE
import dev.sluide.games.doubles.MoveResult
import dev.sluide.games.doubles.Tile
import dev.sluide.games.doubles.fontSize
import javafx.animation.Animation
import javafx.animation.ParallelTransition
import javafx.animation.ScaleTransition
import javafx.animation.SequentialTransition
import javafx.animation.TranslateTransition
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.util.Duration

/**
 * The board of Doubles: empty cells and, on top, one absolutely placed node per tile, keyed by tile id. A move slides
 * the nodes, then shows the new board and pops the merged and spawned tiles; [finishAnimation] jumps to the end.
 */
internal class BoardView : Pane() {
    /** The slide takes this long; the pop 1.2 times as long. Zero shows moves at once. */
    var animationDuration: Duration = Duration.millis(100.0)

    private var tile = MIN_TILE
    private var side = 0
    private var shown: List<Tile> = emptyList()
    private val nodes = mutableMapOf<Int, StackPane>()
    private val cells = mutableListOf<Region>()
    private var active: Animation? = null
    private var activeEnd: () -> Unit = {}

    init {
        styleClass += "dbl-board"
        isFocusTraversable = true
    }

    private val gap get() = tile * GAP_FACTOR

    private fun x(cell: Cell) = gap + cell.col * (tile + gap)

    private fun y(cell: Cell) = gap + cell.row * (tile + gap)

    private val radius get() = "-fx-background-radius: ${tile * RADIUS_FACTOR}"

    /** Sizes the board for tiles of side [tile] and [side] by [side] cells; tiles are laid out again. */
    fun applyTile(
        tile: Double,
        side: Int,
    ) {
        this.tile = tile
        this.side = side
        val extent = side * tile + (side + 1) * gap
        setMinSize(extent, extent)
        setPrefSize(extent, extent)
        setMaxSize(extent, extent)
        style = radius
        children.removeAll(cells)
        cells.clear()
        for (row in 0 until side) {
            for (col in 0 until side) {
                val cell = Cell(row, col)
                cells +=
                    Region().apply {
                        styleClass += "dbl-cell"
                        isManaged = false
                        style = radius
                        isMouseTransparent = true
                        resizeRelocate(x(cell), y(cell), tile, tile)
                    }
            }
        }
        children.addAll(0, cells)
        children.removeAll(nodes.values)
        nodes.clear()
        render(shown)
    }

    /** Shows exactly [tiles] on a board of [side] by [side], without animation. */
    fun show(
        tiles: List<Tile>,
        side: Int,
    ) {
        if (side != this.side) applyTile(tile, side)
        render(tiles)
    }

    private fun render(tiles: List<Tile>) {
        shown = tiles
        val ids = tiles.mapTo(HashSet()) { it.id }
        val gone = nodes.keys.filter { it !in ids }
        children.removeAll(gone.mapNotNull { nodes.remove(it) })
        for (t in tiles) {
            val node = nodes.getOrPut(t.id) { createNode(t).also { children += it } }
            node.translateX = 0.0
            node.translateY = 0.0
            node.scaleX = 1.0
            node.scaleY = 1.0
            node.resizeRelocate(x(t.cell), y(t.cell), tile, tile)
        }
    }

    private fun createNode(t: Tile): StackPane {
        val label =
            Label(t.value.toString()).apply {
                styleClass += "dbl-label"
                when {
                    t.value in DARK_TEXT -> styleClass += "dbl-text-dark"
                    t.value >= HIGH -> styleClass += "dbl-text-gold"
                }
                style = "-fx-font-size: ${fontSize(tile, t.value)}px"
            }
        return StackPane(label).apply {
            styleClass += listOf("dbl-tile", if (t.value > BIG) "dbl-vbig" else "dbl-v${t.value}")
            alignment = Pos.CENTER
            isManaged = false
            isFocusTraversable = false
            isMouseTransparent = true
            style = radius
        }
    }

    /** Slides the tiles of [result], then shows [tiles] and pops the merged and the spawned one. */
    fun animate(
        result: MoveResult,
        tiles: List<Tile>,
        side: Int,
    ) {
        finishAnimation()
        if (animationDuration == Duration.ZERO) {
            show(tiles, side)
            return
        }
        activeEnd = { show(tiles, side) }
        val slides =
            result.slides.mapNotNull { slide ->
                val node = nodes[slide.id] ?: return@mapNotNull null
                TranslateTransition(animationDuration, node).apply {
                    toX = x(slide.to) - x(slide.from)
                    toY = y(slide.to) - y(slide.from)
                }
            }
        val slide = ParallelTransition(*slides.toTypedArray())
        active = slide
        slide.setOnFinished {
            active = null
            show(tiles, side)
            pop(result, tiles, side)
        }
        slide.play()
    }

    private fun pop(
        result: MoveResult,
        tiles: List<Tile>,
        side: Int,
    ) {
        val spawnedId = result.spawned?.id
        val full = animationDuration.multiply(POP_FACTOR)
        val half = full.divide(2.0)
        val pops =
            (result.merged.map { it.id } + listOfNotNull(spawnedId)).mapNotNull { id ->
                val node = nodes[id] ?: return@mapNotNull null
                if (id == spawnedId) {
                    node.scaleX = 0.0
                    node.scaleY = 0.0
                    ScaleTransition(full, node).apply {
                        toX = 1.0
                        toY = 1.0
                    }
                } else {
                    SequentialTransition(
                        ScaleTransition(half, node).apply {
                            toX = MERGE_SCALE
                            toY = MERGE_SCALE
                        },
                        ScaleTransition(half, node).apply {
                            toX = 1.0
                            toY = 1.0
                        },
                    )
                }
            }
        if (pops.isEmpty()) return
        val all = ParallelTransition(*pops.toTypedArray())
        active = all
        activeEnd = { show(tiles, side) }
        all.setOnFinished { active = null }
        all.play()
    }

    /** Stops a running animation and applies its end state at once. */
    fun finishAnimation() {
        val animation = active ?: return
        active = null
        animation.setOnFinished(null)
        animation.stop()
        activeEnd()
    }

    /** The label text of each shown tile by cell, for tests. */
    internal fun tileTexts(): Map<Cell, String> =
        shown.associate { it.cell to (nodes.getValue(it.id).children.first() as Label).text }

    /** Whether every tile node has no translate and a scale of 1, for tests. */
    internal fun nodesSettled(): Boolean =
        nodes.values.all { it.translateX == 0.0 && it.translateY == 0.0 && it.scaleX == 1.0 && it.scaleY == 1.0 }

    /** The number of tile nodes on the board, for tests. */
    internal fun nodeCount(): Int = nodes.size

    private companion object {
        const val RADIUS_FACTOR = 0.08
        const val POP_FACTOR = 1.2
        const val MERGE_SCALE = 1.15
        const val BIG = 8192

        /** Tiles whose light-theme colour is too light for white text. */
        val DARK_TEXT = setOf(2, 4, 8, 2048)
        const val HIGH = 4096
    }
}
