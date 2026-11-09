package dev.sluide.games.pipepuzzle.ui

import dev.sluide.games.pipepuzzle.Dir
import dev.sluide.games.pipepuzzle.Look
import dev.sluide.games.pipepuzzle.Openings
import dev.sluide.games.pipepuzzle.Pos
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.RadialGradient
import javafx.scene.paint.Stop
import javafx.scene.shape.StrokeLineCap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Draws the whole Pipe Puzzle board: background, tiles with their conduits, source and drain, leaks and cursor. */
internal object TilePainter {
    /** Layer widths as fractions of the cell size; 0 skips the layer. */
    private class Widths(
        val halo: Double,
        val outline: Double,
        val body: Double,
        val flow: Double,
        val core: Double,
        val joints: Boolean = false,
    )

    private val WATER = Widths(halo = 0.0, outline = 0.40, body = 0.30, flow = 0.19, core = 0.06, joints = true)
    private val POWER = Widths(halo = 0.55, outline = 0.25, body = 0.17, flow = 0.11, core = 0.04)
    private val LIGHT = Widths(halo = 0.34, outline = 0.0, body = 0.07, flow = 0.09, core = 0.035)

    private enum class Layer { HALO, OUTLINE, BODY, FLOW, CORE }

    private fun widths(look: Look) =
        when (look) {
            Look.WATER -> WATER
            Look.POWER -> POWER
            Look.LIGHT -> LIGHT
        }

    /** The light leak glow, built once per palette and cell size and drawn at the origin. */
    private var leakGradient: RadialGradient? = null
    private var leakGradientKey: Pair<LookPalette, Double>? = null

    private fun leakGradient(
        palette: LookPalette,
        r: Double,
    ): RadialGradient {
        val key = palette to r
        val cached = leakGradient
        if (cached != null && leakGradientKey == key) return cached
        val gradient =
            RadialGradient(
                0.0,
                0.0,
                0.0,
                0.0,
                r,
                false,
                CycleMethod.NO_CYCLE,
                Stop(0.0, palette.flowCore),
                Stop(0.35, palette.leak),
                Stop(1.0, palette.leakFade),
            )
        leakGradient = gradient
        leakGradientKey = key
        return gradient
    }

    /**
     * Clears and draws the whole [geometry] area (width × height, also beyond the board) for one frame. Leaves no
     * fill, stroke or transform behind on [gc].
     */
    fun paint(
        gc: GraphicsContext,
        paint: BoardPaint,
        geometry: BoardGeometry,
        palette: LookPalette,
        look: Look,
    ) {
        gc.save()
        val side = paint.side
        val w = widths(look)
        val cs = geometry.cellSize
        val filled = BooleanArray(side * side)
        paint.filled.forEach {
            if (it.row in 0 until side &&
                it.col in 0 until side
            ) {
                filled[it.row * side + it.col] = true
            }
        }
        val turning = paint.turning?.let { it.row * side + it.col } ?: -1
        val sourceIndex = paint.source.row * side + paint.source.col
        val drainIndex = paint.drain.row * side + paint.drain.col
        gc.fill = palette.surround
        gc.fillRect(0.0, 0.0, geometry.width, geometry.height)
        for (row in 0 until side) {
            for (col in 0 until side) {
                val index = row * side + col
                val fixed = index == sourceIndex || index == drainIndex
                drawBase(gc, geometry.originX + col * cs, geometry.originY + row * cs, cs, fixed, palette)
            }
        }
        // Layer by layer over all tiles, so a halo never covers the body of a neighbour.
        for (layer in Layer.entries) {
            for (row in 0 until side) {
                for (col in 0 until side) {
                    val index = row * side + col
                    if (index == turning) continue
                    val cx = geometry.originX + (col + 0.5) * cs
                    val cy = geometry.originY + (row + 0.5) * cs
                    gc.save()
                    gc.translate(cx, cy)
                    drawConduit(gc, paint.tiles[index], filled[index], layer, cs, palette, w)
                    gc.restore()
                }
            }
        }
        if (turning >= 0) {
            val cx = geometry.originX + (paint.turning!!.col + 0.5) * cs
            val cy = geometry.originY + (paint.turning.row + 0.5) * cs
            gc.save()
            gc.translate(cx, cy)
            // Clipped to the cell, so a halo cannot cover a neighbour while the tile is turned.
            gc.beginPath()
            gc.rect(-cs / 2, -cs / 2, cs, cs)
            gc.clip()
            gc.rotate(paint.turnAngle)
            for (layer in Layer.entries) drawConduit(gc, paint.tiles[turning], filled[turning], layer, cs, palette, w)
            gc.restore()
        }
        val sourceX = geometry.originX + (paint.source.col + 0.5) * cs
        val sourceY = geometry.originY + (paint.source.row + 0.5) * cs
        EndpointPainter.paint(gc, look, true, true, false, sourceX, sourceY, cs, palette)
        val drainX = geometry.originX + (paint.drain.col + 0.5) * cs
        val drainY = geometry.originY + (paint.drain.row + 0.5) * cs
        EndpointPainter.paint(gc, look, false, filled[drainIndex], paint.solved, drainX, drainY, cs, palette)
        for ((pos, dir) in paint.leaks) {
            if (pos.row * side + pos.col == turning && paint.turnAngle != 0.0) continue
            drawLeak(
                gc,
                look,
                geometry.originX + (pos.col + 0.5) * cs,
                geometry.originY + (pos.row + 0.5) * cs,
                dir,
                cs,
                palette,
            )
        }
        paint.cursor?.let { drawCursor(gc, it, geometry, palette) }
        gc.restore()
    }

    private fun drawBase(
        gc: GraphicsContext,
        x: Double,
        y: Double,
        cs: Double,
        fixed: Boolean,
        palette: LookPalette,
    ) {
        gc.fill = if (fixed) palette.fixedBase else palette.tileBase
        gc.fillRect(x, y, cs, cs)
        gc.stroke = palette.grid
        gc.lineWidth = 1.0
        gc.strokeRect(x + 0.5, y + 0.5, cs - 1.0, cs - 1.0)
    }

    /** Draws one layer of a conduit with the origin at the cell centre. */
    private fun drawConduit(
        gc: GraphicsContext,
        openings: Openings,
        filled: Boolean,
        layer: Layer,
        cs: Double,
        palette: LookPalette,
        w: Widths,
    ) {
        if (openings.count == 0) return
        val lit = layer == Layer.HALO || layer == Layer.FLOW || layer == Layer.CORE
        if (lit && !filled) return
        val width =
            when (layer) {
                Layer.HALO -> w.halo
                Layer.OUTLINE -> w.outline
                Layer.BODY -> w.body
                Layer.FLOW -> w.flow
                Layer.CORE -> w.core
            }
        if (width <= 0.0) return
        val color =
            when (layer) {
                Layer.HALO -> palette.haloColor
                Layer.OUTLINE -> palette.conduitOutline
                Layer.BODY -> palette.conduitBody
                Layer.FLOW -> palette.flow
                Layer.CORE -> palette.flowCore
            }
        val px = width * cs
        gc.lineCap = StrokeLineCap.BUTT
        gc.stroke = color
        gc.fill = color
        gc.lineWidth = px
        gc.beginPath()
        for (dir in Dir.entries) {
            if (!openings.has(dir)) continue
            gc.moveTo(0.0, 0.0)
            gc.lineTo(dir.dCol * cs / 2, dir.dRow * cs / 2)
        }
        gc.stroke()
        // The disc closes the corner notch, caps an end and makes the hub of a T or cross a little wider.
        val hub = if (openings.count >= 3 && layer != Layer.CORE && layer != Layer.HALO) 1.3 else 1.0
        val r = px / 2 * hub
        gc.fillOval(-r, -r, 2 * r, 2 * r)
        if (layer == Layer.BODY && w.joints) {
            gc.stroke = palette.joint
            gc.lineWidth = cs * 0.07
            val half = (w.outline + 0.06) * cs / 2
            val at = cs / 2 - cs * 0.07
            for (dir in Dir.entries) {
                if (!openings.has(dir)) continue
                gc.strokeLine(
                    at * dir.dCol - half * dir.dRow,
                    at * dir.dRow - half * dir.dCol,
                    at * dir.dCol + half * dir.dRow,
                    at * dir.dRow + half * dir.dCol,
                )
            }
        }
    }

    private fun drawLeak(
        gc: GraphicsContext,
        look: Look,
        centreX: Double,
        centreY: Double,
        dir: Dir,
        cs: Double,
        palette: LookPalette,
    ) {
        val ex = centreX + dir.dCol * cs * 0.5
        val ey = centreY + dir.dRow * cs * 0.5
        gc.save()
        gc.fill = palette.leak
        gc.stroke = palette.leak
        when (look) {
            Look.WATER -> {
                val r = cs * 0.08
                gc.fillOval(ex - r, ey - r, 2 * r, 2 * r)
                val dx = ex + dir.dCol * cs * 0.1
                val dy = ey + dir.dRow * cs * 0.1 + cs * 0.06
                gc.fillOval(dx - r * 0.7, dy - r * 0.7, r * 1.4, r * 1.4)
            }

            Look.POWER -> {
                gc.lineWidth = maxOf(1.5, cs * 0.04)
                gc.lineCap = StrokeLineCap.ROUND
                val len = cs * 0.14
                for (i in -1..1) {
                    val a = atan2(dir.dRow.toDouble(), dir.dCol.toDouble()) + i * 0.8
                    gc.strokeLine(ex, ey, ex + len * cos(a), ey + len * sin(a))
                }
            }

            Look.LIGHT -> {
                val r = cs * 0.17
                gc.translate(ex, ey)
                gc.fill = leakGradient(palette, r)
                gc.fillOval(-r, -r, 2 * r, 2 * r)
            }
        }
        gc.restore()
    }

    private fun drawCursor(
        gc: GraphicsContext,
        pos: Pos,
        geometry: BoardGeometry,
        palette: LookPalette,
    ) {
        val cs = geometry.cellSize
        val inset = 2.0
        gc.stroke = palette.cursor
        gc.lineWidth = 2.5
        gc.strokeRoundRect(
            geometry.cellX(pos) + inset,
            geometry.cellY(pos) + inset,
            cs - 2 * inset,
            cs - 2 * inset,
            cs * 0.25,
            cs * 0.25,
        )
    }
}
