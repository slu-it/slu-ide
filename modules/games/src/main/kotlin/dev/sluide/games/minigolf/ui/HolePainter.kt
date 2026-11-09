package dev.sluide.games.minigolf.ui

import dev.sluide.games.common.ui.FieldPlacement
import dev.sluide.games.minigolf.Hole
import dev.sluide.games.minigolf.Rules
import dev.sluide.games.minigolf.geometry.Polygon
import dev.sluide.games.minigolf.geometry.Vec
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Stop
import javafx.scene.shape.StrokeLineCap
import javafx.scene.shape.StrokeLineJoin
import kotlin.math.PI
import kotlin.math.sin

/**
 * Draws one hole in field units: the dark surround, rails outside the greens (a green's outline is the collision line,
 * the ball stops [Rules.BALL_RADIUS] inside it), felt, slopes, sand, water, blocks, bumpers, tunnels, the cup, the
 * moving obstacles and the ball. Obstacles and the cup are in [ObjectPainter]. Nothing is clipped to a polygon, since
 * that is slow in JavaFX: grain, ripples and arrows are placed inside their areas by [TerrainCache].
 */
internal object HolePainter {
    private const val RAIL_WIDTH = 7.0
    private const val STRIPE = 12.0
    private const val MAX_RIPPLE = 4.0

    private val FELT =
        gradient(
            Vec(0.0, Rules.HUD_HEIGHT),
            Vec(0.0, Rules.FIELD_HEIGHT),
            MiniGolfPalette.FELT_LIGHT,
            MiniGolfPalette.FELT_DARK,
        )

    /** Mown stripes: a repeating gradient across the field, so that they need no clip. */
    private val STRIPES =
        LinearGradient(
            0.0,
            0.0,
            STRIPE,
            0.0,
            false,
            CycleMethod.REPEAT,
            Stop(0.0, Color.color(1.0, 1.0, 1.0, 0.035)),
            Stop(0.5, Color.color(1.0, 1.0, 1.0, 0.035)),
            Stop(0.5, Color.TRANSPARENT),
            Stop(1.0, Color.TRANSPARENT),
        )

    private val SLOPE_SHADE = Pair(Color.color(1.0, 1.0, 1.0, 0.07), Color.color(0.0, 0.0, 0.0, 0.16))

    private var cache: TerrainCache? = null

    private fun cacheFor(hole: Hole): TerrainCache =
        cache?.takeIf { it.hole === hole } ?: TerrainCache(hole).also { cache = it }

    /**
     * Paints [hole] as it looks at [step] (the moving obstacles' position). The ball is drawn at [ball] when
     * [ballVisible] (not while it has dropped into the cup).
     */
    fun paint(
        gc: GraphicsContext,
        placement: FieldPlacement,
        hole: Hole,
        step: Long,
        ball: Vec,
        ballVisible: Boolean,
    ) {
        val terrain = cacheFor(hole)
        gc.inField(placement) {
            fill = MiniGolfPalette.SURROUND
            fillRect(0.0, 0.0, Rules.FIELD_WIDTH, Rules.FIELD_HEIGHT)
            hole.greens.forEach { rail(it) }
            hole.greens.forEach { felt(it) }
            hole.slopes.forEachIndexed { i, slope -> slope(slope.area, slope.acceleration, terrain.arrows[i]) }
            hole.sand.forEachIndexed { i, area -> sand(area, terrain.sandDark[i], terrain.sandLight[i]) }
            hole.water.forEachIndexed { i, area -> water(area, terrain.ripples[i], step) }
            ObjectPainter.paint(this, hole, step, ball, ballVisible)
        }
    }

    private fun GraphicsContext.rail(green: Polygon) {
        polygonPath(green)
        lineJoin = StrokeLineJoin.ROUND
        stroke = MiniGolfPalette.RAIL_EDGE
        lineWidth = RAIL_WIDTH + 2
        stroke()
        stroke = MiniGolfPalette.RAIL
        lineWidth = RAIL_WIDTH
        stroke()
    }

    /** The felt over the inner half of the rail stroke, so that the rail shows outside the collision line. */
    private fun GraphicsContext.felt(green: Polygon) {
        polygonPath(green)
        fill = FELT
        fill()
        fill = STRIPES
        fill()
        // The rail's light inner edge, a thin line on the outline itself.
        stroke = MiniGolfPalette.RAIL_LIGHT
        lineWidth = 0.9
        stroke()
    }

    private fun GraphicsContext.slope(
        area: Polygon,
        acceleration: Vec,
        arrows: List<SlopeArrow>,
    ) {
        val direction = acceleration.normalized()
        val xs = area.points.map { it.x }
        val ys = area.points.map { it.y }
        val center = Vec((xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2)
        val half = maxOf(xs.max() - xs.min(), ys.max() - ys.min()) / 2
        polygonPath(area)
        fill = gradient(center - direction * half, center + direction * half, SLOPE_SHADE.first, SLOPE_SHADE.second)
        fill()
        stroke = MiniGolfPalette.SLOPE_ARROW
        fill = MiniGolfPalette.SLOPE_ARROW
        lineWidth = 1.0
        lineCap = StrokeLineCap.ROUND
        arrows.forEach { arrow(it.from, it.to, 3.0) }
    }

    private fun GraphicsContext.sand(
        area: Polygon,
        dark: List<Vec>,
        light: List<Vec>,
    ) {
        polygonPath(area)
        fill = MiniGolfPalette.SAND
        fill()
        globalAlpha = 0.55
        fill = MiniGolfPalette.SAND_DARK
        dark.forEach { fillRect(it.x, it.y, 0.8, 0.8) }
        fill = MiniGolfPalette.SAND_LIGHT
        light.forEach { fillRect(it.x, it.y, 0.8, 0.8) }
        globalAlpha = 1.0
        polygonPath(area)
        stroke = MiniGolfPalette.SAND_DARK
        lineWidth = 1.0
        lineJoin = StrokeLineJoin.ROUND
        stroke()
    }

    private fun GraphicsContext.water(
        area: Polygon,
        ripples: List<Ripple>,
        step: Long,
    ) {
        val ys = area.points.map { it.y }
        polygonPath(area)
        fill = gradient(Vec(0.0, ys.min()), Vec(0.0, ys.max()), MiniGolfPalette.WATER, MiniGolfPalette.WATER_DEEP)
        fill()
        lineWidth = 0.7
        ripples.forEach {
            val phase = (step / 90.0 + it.phase) % 1.0
            val r = 1.5 + phase * (MAX_RIPPLE - 1.5)
            stroke = Color.color(0.75, 0.9, 1.0, 0.35 * (1 - phase))
            strokeOval(it.center.x - r, it.center.y - r * 0.55, r * 2, r * 1.1)
        }
        polygonPath(area)
        stroke = MiniGolfPalette.WATER_EDGE
        globalAlpha = 0.85 + 0.1 * sin(step / 30.0 * PI)
        lineWidth = 1.6
        lineJoin = StrokeLineJoin.ROUND
        stroke()
        globalAlpha = 1.0
    }
}
