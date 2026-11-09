package dev.sluide.games.minigolf.ui

import dev.sluide.games.minigolf.Hole
import dev.sluide.games.minigolf.Mover
import dev.sluide.games.minigolf.RotatingBar
import dev.sluide.games.minigolf.Rules
import dev.sluide.games.minigolf.SlidingBlock
import dev.sluide.games.minigolf.Tunnel
import dev.sluide.games.minigolf.geometry.Segment
import dev.sluide.games.minigolf.geometry.Vec
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import javafx.scene.shape.StrokeLineCap
import javafx.scene.shape.StrokeLineJoin

/**
 * The solid things of a hole, drawn over the terrain by [HolePainter]: blocks, walls, bumpers, tunnels, cup, movers,
 * ball and flag.
 */
internal object ObjectPainter {
    fun paint(
        gc: GraphicsContext,
        hole: Hole,
        step: Long,
        ball: Vec,
        ballVisible: Boolean,
    ) {
        with(gc) {
            hole.blocks.forEach { block ->
                polygonPath(block)
                fill =
                    gradient(
                        Vec(0.0, Rules.HUD_HEIGHT),
                        Vec(0.0, Rules.FIELD_HEIGHT),
                        MiniGolfPalette.RAIL_LIGHT,
                        MiniGolfPalette.RAIL,
                    )
                fill()
                stroke = MiniGolfPalette.RAIL_EDGE
                lineWidth = 1.4
                lineJoin = StrokeLineJoin.ROUND
                stroke()
            }
            hole.looseWalls.forEach { wall(it) }
            hole.bumpers.forEach { bumper(it.center, it.radius) }
            hole.tunnels.forEach { tunnel(it) }
            cup(hole.cup)
            hole.movers.forEach { mover(it, step) }
            flag(hole.cup)
            if (ballVisible) ball(ball)
        }
    }

    private fun GraphicsContext.wall(wall: Segment) {
        lineCap = StrokeLineCap.ROUND
        stroke = MiniGolfPalette.RAIL_EDGE
        lineWidth = 1.9
        strokeLine(wall.a.x, wall.a.y, wall.b.x, wall.b.y)
        stroke = MiniGolfPalette.RAIL_LIGHT
        lineWidth = 1.3
        strokeLine(wall.a.x, wall.a.y, wall.b.x, wall.b.y)
    }

    private fun GraphicsContext.bumper(
        center: Vec,
        radius: Double,
    ) {
        fill = MiniGolfPalette.SHADOW
        fillCircle(center + Vec(1.2, 1.6), radius)
        fill = radial(center, radius, MiniGolfPalette.BUMPER_RING, MiniGolfPalette.BUMPER)
        fillCircle(center, radius)
        stroke = MiniGolfPalette.BUMPER_DARK
        lineWidth = 1.2
        strokeCircle(center, radius)
        stroke = MiniGolfPalette.BUMPER_RING
        lineWidth = 1.0
        strokeCircle(center, radius * 0.6)
    }

    private fun GraphicsContext.tunnel(tunnel: Tunnel) {
        mouth(tunnel.a, tunnel.exitA)
        mouth(tunnel.b, tunnel.exitB)
    }

    private fun GraphicsContext.mouth(
        center: Vec,
        exit: Vec,
    ) {
        val radius = Rules.TUNNEL_RADIUS
        fill = MiniGolfPalette.TUNNEL_HOLE
        fillCircle(center, radius)
        stroke = MiniGolfPalette.TUNNEL_RING
        lineWidth = 1.5
        strokeCircle(center, radius)
        stroke = MiniGolfPalette.TUNNEL_ARROW
        fill = MiniGolfPalette.TUNNEL_ARROW
        lineWidth = 1.0
        lineCap = StrokeLineCap.ROUND
        arrow(center - exit * 1.5, center + exit * 3.2, 2.8)
    }

    private fun GraphicsContext.cup(center: Vec) {
        fill = MiniGolfPalette.CUP_RIM
        fillCircle(center, Rules.CUP_RADIUS + 0.9)
        fill = MiniGolfPalette.CUP
        fillCircle(center, Rules.CUP_RADIUS)
    }

    /** A pole and a pennant standing at the cup; drawn before the ball, so that the ball is never hidden by it. */
    private fun GraphicsContext.flag(cup: Vec) {
        val top = cup + Vec(0.0, -POLE)
        stroke = MiniGolfPalette.FLAG_POLE
        lineWidth = 0.9
        lineCap = StrokeLineCap.ROUND
        strokeLine(cup.x, cup.y, top.x, top.y)
        fill = MiniGolfPalette.FLAG
        fillPolygon(
            doubleArrayOf(top.x, top.x + 8.0, top.x),
            doubleArrayOf(top.y, top.y + 2.8, top.y + 5.6),
            3,
        )
    }

    private fun GraphicsContext.mover(
        mover: Mover,
        step: Long,
    ) {
        when (mover) {
            is RotatingBar -> bar(mover, step)
            is SlidingBlock -> slidingBlock(mover, step)
        }
    }

    private fun GraphicsContext.bar(
        bar: RotatingBar,
        step: Long,
    ) {
        val s = bar.segmentsAt(step).first()
        lineCap = StrokeLineCap.ROUND
        stroke = MiniGolfPalette.SHADOW
        lineWidth = bar.thickness
        strokeLine(s.a.x + 1.2, s.a.y + 1.6, s.b.x + 1.2, s.b.y + 1.6)
        stroke = MiniGolfPalette.STEEL_DARK
        lineWidth = bar.thickness + 1.2
        strokeLine(s.a.x, s.a.y, s.b.x, s.b.y)
        stroke = MiniGolfPalette.STEEL
        lineWidth = bar.thickness
        strokeLine(s.a.x, s.a.y, s.b.x, s.b.y)
        stroke = MiniGolfPalette.STEEL_LIGHT
        lineWidth = bar.thickness * 0.3
        strokeLine(s.a.x, s.a.y, s.b.x, s.b.y)
        fill = MiniGolfPalette.STEEL_DARK
        fillCircle(bar.pivot, bar.thickness * 0.85)
        fill = MiniGolfPalette.STEEL_LIGHT
        fillCircle(bar.pivot, bar.thickness * 0.45)
    }

    private fun GraphicsContext.slidingBlock(
        block: SlidingBlock,
        step: Long,
    ) {
        val c = block.centerAt(step)
        val x = c.x - block.width / 2
        val y = c.y - block.height / 2
        fill = MiniGolfPalette.SHADOW
        fillRect(x + 1.2, y + 1.6, block.width, block.height)
        fill = gradient(Vec(x, y), Vec(x, y + block.height), MiniGolfPalette.RAIL_LIGHT, MiniGolfPalette.RAIL)
        fillRect(x, y, block.width, block.height)
        stroke = MiniGolfPalette.RAIL_EDGE
        lineWidth = 1.4
        strokeRect(x, y, block.width, block.height)
        stroke = Color.color(1.0, 1.0, 1.0, 0.25)
        lineWidth = 0.7
        strokeRect(x + 1.5, y + 1.5, block.width - 3, block.height - 3)
    }

    private fun GraphicsContext.ball(at: Vec) {
        val r = Rules.BALL_RADIUS
        fill = MiniGolfPalette.SHADOW
        fillOval(at.x - r + 0.9, at.y - r + 1.4, r * 2, r * 2)
        fill = radial(at, r, MiniGolfPalette.BALL, MiniGolfPalette.BALL_SHADE)
        fillCircle(at, r)
    }

    private const val POLE = 18.0

    private val BLOCK_FILL =
        gradient(
            Vec(0.0, Rules.HUD_HEIGHT),
            Vec(0.0, Rules.FIELD_HEIGHT),
            MiniGolfPalette.RAIL_LIGHT,
            MiniGolfPalette.RAIL,
        )
}
