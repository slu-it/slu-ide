package dev.sluide.games.wordguess.ui

import dev.sluide.games.wordguess.GAP_FACTOR
import dev.sluide.games.wordguess.LetterState
import dev.sluide.games.wordguess.MIN_TILE
import dev.sluide.games.wordguess.Tile
import javafx.animation.Animation
import javafx.animation.Interpolator
import javafx.animation.TranslateTransition
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.util.Duration

/** The rows of tiles. Only shows what [update] gets; it owns the keyboard focus for the typing keys. */
internal class GridView : VBox() {
    private val rowBoxes = mutableListOf<HBox>()
    private var tiles: List<List<Label>> = emptyList()
    private var shown: List<List<Tile>> = emptyList()
    private var size = MIN_TILE
    private var shake: TranslateTransition? = null

    /** A rejected row is shaking right now. */
    val isShaking: Boolean get() = shake?.status == Animation.Status.RUNNING

    init {
        styleClass += "wg-grid"
        alignment = Pos.CENTER
        isFocusTraversable = true
    }

    /** Sets the tile side and redraws everything at it. */
    fun applyTile(side: Double) {
        size = side
        spacing = side * GAP_FACTOR
        rowBoxes.forEach { it.spacing = side * GAP_FACTOR }
        tiles.flatten().forEach { it.fix(side) }
    }

    /** Redraws the tiles from [rows] (row by row, tile by tile; unchanged tiles are left alone). */
    fun update(rows: List<List<Tile>>) {
        if (tiles.size != rows.size) build(rows)
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, tile ->
                if (shown.getOrNull(r)?.getOrNull(c) != tile) draw(tiles[r][c], tile)
            }
        }
        shown = rows
    }

    /** Shakes the row briefly, for a rejected word. */
    fun shake(row: Int) {
        val box = rowBoxes.getOrNull(row) ?: return
        shake?.stop()
        shake?.node?.translateX = 0.0
        shake =
            TranslateTransition(Duration.millis(SHAKE_STEP_MILLIS), box).apply {
                fromX = -size * SHAKE_FACTOR
                toX = size * SHAKE_FACTOR
                cycleCount = SHAKE_CYCLES
                isAutoReverse = true
                interpolator = Interpolator.LINEAR
                setOnFinished { box.translateX = 0.0 }
                play()
            }
    }

    private fun build(rows: List<List<Tile>>) {
        tiles =
            rows.map { row ->
                List(row.size) {
                    Label().apply {
                        alignment = Pos.CENTER
                        fix(size)
                    }
                }
            }
        rowBoxes.clear()
        rowBoxes += tiles.map { HBox(size * GAP_FACTOR, *it.toTypedArray()).apply { alignment = Pos.CENTER } }
        children.setAll(rowBoxes)
        shown = emptyList()
    }

    private fun Label.fix(side: Double) {
        setMinSize(side, side)
        setPrefSize(side, side)
        setMaxSize(side, side)
        style = "-fx-font-size: ${(side * FONT_FACTOR).toInt()}px;"
    }

    private fun draw(
        label: Label,
        tile: Tile,
    ) {
        label.text = tile.letter?.toString() ?: ""
        label.styleClass.setAll(
            "wg-tile",
            when (tile.state) {
                LetterState.CORRECT -> "wg-correct"
                LetterState.PRESENT -> "wg-present"
                LetterState.ABSENT -> "wg-absent"
                null -> if (tile.letter == null) "wg-empty" else "wg-typed"
            },
        )
    }

    private companion object {
        const val FONT_FACTOR = 0.5
        const val SHAKE_STEP_MILLIS = 50.0
        const val SHAKE_CYCLES = 6
        const val SHAKE_FACTOR = 0.12
    }
}
