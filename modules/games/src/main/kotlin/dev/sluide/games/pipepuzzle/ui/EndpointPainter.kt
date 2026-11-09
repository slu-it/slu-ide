package dev.sluide.games.pipepuzzle.ui

import dev.sluide.games.pipepuzzle.Look
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.RadialGradient
import javafx.scene.paint.Stop
import javafx.scene.shape.StrokeLineCap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The symbols of the source and the drain, drawn over the cell centre of the fixed tiles. */
internal object EndpointPainter {
    /** [on]: the symbol shows the flow (the source always, the drain once reached); [glow]: the solved halo. */
    fun paint(
        gc: GraphicsContext,
        look: Look,
        isSource: Boolean,
        on: Boolean,
        glow: Boolean,
        cx: Double,
        cy: Double,
        cs: Double,
        palette: LookPalette,
    ) {
        gc.save()
        gc.translate(cx, cy)
        if (glow) halo(gc, cs * 0.55, palette.flow)
        when (look) {
            Look.WATER -> if (isSource) tap(gc, cs, palette) else grate(gc, cs, on, palette)
            Look.POWER -> if (isSource) plant(gc, cs, palette) else lamp(gc, cs, on, palette)
            Look.LIGHT -> if (isSource) emitter(gc, cs, palette) else target(gc, cs, on, palette)
        }
        gc.restore()
    }

    private fun halo(
        gc: GraphicsContext,
        r: Double,
        color: Color,
    ) {
        gc.fill =
            RadialGradient(
                0.0,
                0.0,
                0.0,
                0.0,
                r,
                false,
                CycleMethod.NO_CYCLE,
                Stop(0.5, Color.color(color.red, color.green, color.blue, 0.55)),
                Stop(1.0, Color.color(color.red, color.green, color.blue, 0.0)),
            )
        gc.fillOval(-r, -r, 2 * r, 2 * r)
    }

    private fun disc(
        gc: GraphicsContext,
        r: Double,
        color: Color,
    ) {
        gc.fill = color
        gc.fillOval(-r, -r, 2 * r, 2 * r)
    }

    private fun ring(
        gc: GraphicsContext,
        r: Double,
        width: Double,
        color: Color,
    ) {
        gc.stroke = color
        gc.lineWidth = width
        gc.strokeOval(-r, -r, 2 * r, 2 * r)
    }

    /** A valve wheel: rim, four spokes and a hub. */
    private fun tap(
        gc: GraphicsContext,
        cs: Double,
        p: LookPalette,
    ) {
        val r = cs * 0.27
        disc(gc, r + cs * 0.04, p.conduitOutline)
        disc(gc, r, p.conduitBody)
        gc.stroke = p.symbolAccent
        gc.lineWidth = maxOf(1.5, cs * 0.06)
        gc.lineCap = StrokeLineCap.ROUND
        for (i in 0 until 4) {
            val a = PI / 4 + i * PI / 2
            gc.strokeLine(0.0, 0.0, r * cos(a), r * sin(a))
        }
        ring(gc, r, cs * 0.07, p.symbolAccent)
        disc(gc, cs * 0.08, p.flowCore)
    }

    /** A round grate: dark hole behind bars, water shining through once filled. */
    private fun grate(
        gc: GraphicsContext,
        cs: Double,
        on: Boolean,
        p: LookPalette,
    ) {
        val r = cs * 0.28
        disc(gc, r + cs * 0.05, p.symbolAccent)
        disc(gc, r, if (on) p.flow else p.grateHole)
        gc.stroke = p.symbol
        gc.lineWidth = maxOf(1.5, cs * 0.06)
        gc.lineCap = StrokeLineCap.BUTT
        for (i in -1..1) {
            gc.strokeLine(i * r * 0.55, -r * 0.85, i * r * 0.55, r * 0.85)
        }
        ring(gc, r, cs * 0.05, p.symbol)
    }

    /** A small power plant: a box with a bolt. */
    private fun plant(
        gc: GraphicsContext,
        cs: Double,
        p: LookPalette,
    ) {
        val h = cs * 0.27
        gc.fill = p.symbol
        gc.fillRoundRect(-h, -h, 2 * h, 2 * h, cs * 0.12, cs * 0.12)
        gc.stroke = p.symbolAccent
        gc.lineWidth = maxOf(1.5, cs * 0.05)
        gc.strokeRoundRect(-h, -h, 2 * h, 2 * h, cs * 0.12, cs * 0.12)
        gc.fill = p.symbolAccent
        val b = cs * 0.2
        gc.fillPolygon(
            doubleArrayOf(b * 0.15, -b * 0.55, -b * 0.05, -b * 0.15, b * 0.55, b * 0.05),
            doubleArrayOf(-b, b * 0.1, b * 0.1, b, -b * 0.1, -b * 0.1),
            6,
        )
    }

    /** A bulb: a glass dome on a screw base; it lights when the flow arrives. */
    private fun lamp(
        gc: GraphicsContext,
        cs: Double,
        on: Boolean,
        p: LookPalette,
    ) {
        if (on) halo(gc, cs * 0.5, p.flow)
        val r = cs * 0.2
        gc.fill = p.symbol
        gc.fillRoundRect(-r * 0.7, r * 0.7, r * 1.4, cs * 0.14, 3.0, 3.0)
        disc(gc, r, if (on) p.flowCore else p.lampOff)
        if (on) {
            disc(gc, r * 0.7, p.flow)
            disc(gc, r * 0.4, p.flowCore)
        }
        ring(gc, r, maxOf(1.5, cs * 0.05), p.symbolAccent)
    }

    /** An emitter: a ring with a bright centre, always on. */
    private fun emitter(
        gc: GraphicsContext,
        cs: Double,
        p: LookPalette,
    ) {
        halo(gc, cs * 0.4, p.flow)
        ring(gc, cs * 0.24, maxOf(2.0, cs * 0.07), p.symbolAccent)
        disc(gc, cs * 0.1, p.flowCore)
    }

    /** A target: concentric rings, lit when the flow arrives. */
    private fun target(
        gc: GraphicsContext,
        cs: Double,
        on: Boolean,
        p: LookPalette,
    ) {
        val color = if (on) p.flow else p.symbol
        if (on) halo(gc, cs * 0.45, p.flow)
        ring(gc, cs * 0.27, maxOf(2.0, cs * 0.06), color)
        ring(gc, cs * 0.16, maxOf(1.5, cs * 0.05), color)
        disc(gc, cs * 0.07, if (on) p.flowCore else p.symbol)
    }
}
