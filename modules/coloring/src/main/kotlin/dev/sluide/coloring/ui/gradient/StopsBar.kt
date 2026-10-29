package dev.sluide.coloring.ui.gradient

import dev.sluide.coloring.analysis.Gradient
import dev.sluide.coloring.analysis.previewStops
import dev.sluide.coloring.ui.Swatch
import dev.sluide.coloring.ui.toFxColor
import dev.sluide.coloring.ui.toFxColorClipped
import javafx.css.PseudoClass
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Stop
import javafx.scene.shape.Rectangle

/**
 * The gradient as a bar with a handle per stop below it. A press selects a handle ([onSelect]), dragging moves it
 * ([onMove] with the position 0..1 under the pointer). A moved stop may pass its neighbours and so change its index;
 * the drag goes on with the selected stop, which the caller keeps on the moved one. The handles are reused while
 * the number of stops stays, so a drag survives the updates it causes.
 */
internal class StopsBar(
    private val onSelect: (Int) -> Unit,
    private val onMove: (Double) -> Unit,
) : Pane() {
    private val checker = Rectangle().apply { fill = Swatch.CHECKER }
    private val bar = Rectangle()
    private val handles = mutableListOf<Region>()
    private var positions = emptyList<Double>()
    private var selectedIndex = 0

    init {
        styleClass += "gradient-stops-bar"
        setMinHeight(HEIGHT)
        setPrefHeight(HEIGHT)
        setMaxHeight(HEIGHT)
        children += listOf(checker, bar)
    }

    /** Shows [gradient] with the stop [selected] marked. */
    fun show(
        gradient: Gradient,
        selected: Int,
    ) {
        selectedIndex = selected
        positions = gradient.stops.map { it.position }
        while (handles.size > positions.size) children.remove(handles.removeLast())
        while (handles.size < positions.size) addHandle()
        val sampled = previewStops(gradient).map { Stop(it.position, it.color.toFxColorClipped()) }
        bar.fill = LinearGradient(0.0, 0.0, 1.0, 0.0, true, CycleMethod.NO_CYCLE, sampled)
        gradient.stops.forEachIndexed { i, stop ->
            handles[i].background = Background(BackgroundFill(stop.color.toFxColor(), null, null))
            handles[i].pseudoClassStateChanged(SELECTED, i == selected)
        }
        handles.getOrNull(selected)?.toFront()
        requestLayout()
    }

    /** The handle of stop [index]; for tests. */
    internal fun handle(index: Int): Region = handles[index]

    private fun addHandle() {
        val index = handles.size
        val handle =
            Region().apply {
                styleClass += "gradient-stop-handle"
                setPrefSize(HANDLE_WIDTH, HANDLE_HEIGHT)
                setMinSize(HANDLE_WIDTH, HANDLE_HEIGHT)
                setMaxSize(HANDLE_WIDTH, HANDLE_HEIGHT)
                addEventHandler(MouseEvent.MOUSE_PRESSED) { onSelect(index) }
                addEventHandler(MouseEvent.MOUSE_DRAGGED) { e ->
                    onMove((this@StopsBar.sceneToLocal(e.sceneX, 0.0).x - PAD) / trackWidth())
                }
            }
        handles += handle
        children += handle
    }

    private fun trackWidth() = (width - 2 * PAD).coerceAtLeast(1.0)

    override fun layoutChildren() {
        for (r in listOf(checker, bar)) {
            r.x = PAD
            r.y = 0.0
            r.width = trackWidth()
            r.height = BAR_HEIGHT
        }
        handles.forEachIndexed { i, h ->
            h.resizeRelocate(
                PAD + positions[i] * trackWidth() - HANDLE_WIDTH / 2,
                BAR_HEIGHT + 2,
                HANDLE_WIDTH,
                HANDLE_HEIGHT,
            )
        }
    }

    companion object {
        private val SELECTED = PseudoClass.getPseudoClass("selected")
        private const val PAD = 8.0
        private const val BAR_HEIGHT = 24.0
        private const val HANDLE_WIDTH = 14.0
        private const val HANDLE_HEIGHT = 18.0
        private const val HEIGHT = BAR_HEIGHT + HANDLE_HEIGHT + 4
    }
}
