package dev.sluide.games.pipepuzzle.ui

import dev.sluide.games.common.ui.FitArea
import dev.sluide.games.pipepuzzle.KeyAction
import dev.sluide.games.pipepuzzle.PipePuzzleModel
import dev.sluide.games.pipepuzzle.Pos
import dev.sluide.games.pipepuzzle.keyAction
import javafx.animation.AnimationTimer
import javafx.scene.canvas.Canvas
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import kotlin.math.max

/**
 * The direction of a click: primary turns clockwise, secondary counter-clockwise, and so does primary with Ctrl (a
 * secondary click on macOS); null for any other button. Pure.
 */
internal fun turnClockwise(
    button: MouseButton,
    ctrlDown: Boolean,
): Boolean? =
    when (button) {
        MouseButton.PRIMARY -> !ctrlDown
        MouseButton.SECONDARY -> false
        else -> null
    }

/**
 * The board drawn into the whole area it gets: its cells are the Fit size of the inside of [area] (the scroll pane
 * around the board) times [zoom] (ADR 0155), and its minimum size is the zoomed board, so that it scrolls above Fit
 * ([FitArea]). Clicks turn tiles, keys become [KeyAction]s for [onAction], and the
 * turn and win animations run on one timer that exists only while an animation runs. FX thread. [clock] gives
 * milliseconds; tests replace it.
 */
internal class BoardCanvas(
    private val model: PipePuzzleModel,
    area: Region,
    private val onAction: (KeyAction) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) : Canvas() {
    private val animation = BoardAnimation()
    private var flowLayers: List<List<Pos>> = emptyList()
    private var minimumSide = 0
    private val fitArea =
        FitArea(area) {
            parent?.requestLayout()
            redraw()
        }

    /** The factor on the Fit size of the cells (ADR 0155). */
    var zoom = 1.0
        set(value) {
            if (field != value) {
                field = value
                parent?.requestLayout()
                redraw()
            }
        }

    private val timer =
        object : AnimationTimer() {
            override fun handle(now: Long) {
                // One reading per frame: an animation that ends between two reads would leave its last frame.
                val time = clock()
                redraw(time)
                if (!animation.isRunning(time)) stop()
            }
        }

    init {
        isFocusTraversable = true
        widthProperty().addListener { _ -> redraw() }
        heightProperty().addListener { _ -> redraw() }
        focusedProperty().addListener { _ -> redraw() }
        setOnMousePressed(::pressed)
        setOnKeyPressed(::keyPressed)
    }

    override fun isResizable(): Boolean = true

    override fun minWidth(height: Double): Double = side() * zoomedCell()

    override fun minHeight(width: Double): Double = side() * zoomedCell()

    override fun prefWidth(height: Double): Double = max(side() * PREF_CELL, minWidth(height))

    override fun prefHeight(width: Double): Double = max(side() * PREF_CELL, minHeight(width))

    override fun maxWidth(height: Double): Double = Double.MAX_VALUE

    override fun maxHeight(width: Double): Double = Double.MAX_VALUE

    override fun resize(
        width: Double,
        height: Double,
    ) {
        this.width = width
        this.height = height
    }

    private fun side(): Int = model.game?.side ?: model.size.side

    private fun zoomedCell(): Double = BoardGeometry.cellFor(fitArea.innerWidth, fitArea.innerHeight, side(), zoom)

    /** Where the board lies in the current area. */
    internal fun geometry(): BoardGeometry =
        BoardGeometry(width, height, side(), zoom, fitArea.innerWidth, fitArea.innerHeight)

    /** The tile just turned animates from its old angle. */
    fun turned(
        pos: Pos,
        clockwise: Boolean,
    ) {
        animation.startTurn(pos, clockwise, clock())
        run()
    }

    /** The puzzle was just solved: the flow runs out from the source. */
    fun solved() {
        val layers = model.game?.fillOrder().orEmpty()
        flowLayers = layers
        animation.startFlow(layers.size, clock())
        run()
    }

    /** Ends the running animations at once. */
    fun finishAnimations() {
        animation.finish()
        timer.stop()
    }

    fun dispose() {
        finishAnimations()
    }

    private fun run() {
        val now = clock()
        redraw(now)
        if (animation.isRunning(now)) timer.start()
    }

    private fun pressed(event: MouseEvent) {
        requestFocus()
        event.consume()
        if (model.game == null) return
        val pos = geometry().posAt(event.x, event.y) ?: return
        val clockwise = turnClockwise(event.button, event.isControlDown) ?: return
        model.turn(pos, clockwise)
    }

    private fun keyPressed(event: KeyEvent) {
        val other =
            event.isAltDown ||
                (event.isControlDown && !event.isShortcutDown) ||
                (event.isMetaDown && !event.isShortcutDown)
        val action = keyAction(event.code, event.isShiftDown, event.isShortcutDown, other) ?: return
        onAction(action)
        event.consume()
    }

    /** True while a turn or the win flow is still running. */
    internal val isAnimating: Boolean get() = animation.isRunning(clock())

    fun redraw(now: Long = clock()) {
        if (width <= 0 || height <= 0) return
        if (minimumSide != side()) {
            minimumSide = side()
            parent?.requestLayout()
        }
        val look = model.look
        val palette = LookPalette.of(look)
        val geometry = geometry()
        val gc = graphicsContext2D
        val game = model.game
        if (game == null) {
            animation.finishFlow()
            gc.fill = palette.surround
            gc.fillRect(0.0, 0.0, width, height)
            return
        }
        // A flow belongs to the solved game: a restart or a new game ends it.
        if (!game.isSolved) animation.finishFlow()
        val side = game.side
        val layers = animation.layersAt(now)
        val filled = if (layers == null) game.filled else flowLayers.take(layers).flatten().toSet()
        val turning = animation.turningAt(now)
        val paint =
            BoardPaint(
                side = side,
                tiles = (0 until side * side).map { game.tile(Pos(it / side, it % side)) },
                source = game.puzzle.source,
                drain = game.puzzle.drain,
                filled = filled,
                leaks = game.leaks,
                cursor = if (isFocused) model.cursor else null,
                turning = turning,
                turnAngle = animation.turnAngleAt(now),
                solved = game.isSolved && layers == null,
            )
        TilePainter.paint(gc, paint, geometry, palette, look)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val PREF_CELL = 44.0
    }
}
