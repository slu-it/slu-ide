package dev.sluide.games.minigolf.ui

import dev.sluide.games.common.ui.FieldPlacement
import dev.sluide.games.minigolf.Rules
import dev.sluide.games.minigolf.geometry.Vec
import javafx.geometry.VPos
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import javafx.scene.text.TextAlignment
import kotlin.math.PI
import kotlin.math.exp
import kotlin.random.Random

/** One spark of the burst at some age: where it is, how big and how visible. */
internal data class Spark(
    val position: Vec,
    val size: Double,
    val alpha: Double,
    val color: Color,
)

/**
 * The 1.5 s burst of sparks at the cup and "Hole in one!" after a hole in one. The sparks fly out, slow down and fall;
 * their position is a function of their age, which counts game steps (they freeze while the game is paused).
 */
internal class HoleInOneBurst(
    private val random: Random = Random.Default,
) {
    private class Seed(
        val direction: Vec,
        val speed: Double,
        val size: Double,
        val color: Color,
    )

    private var origin = Vec.ZERO
    private var startStep = 0L
    private var seeds = emptyList<Seed>()

    private var started = false

    fun start(
        at: Vec,
        step: Long,
    ) {
        origin = at
        startStep = step
        seeds =
            List(COUNT) {
                Seed(
                    Vec.ofAngle(random.nextDouble(2 * PI)),
                    random.nextDouble(30.0, 130.0),
                    random.nextDouble(1.0, 2.4),
                    COLORS[random.nextInt(COLORS.size)],
                )
            }
        started = true
    }

    fun ageSeconds(step: Long): Double = (step - startStep) * Rules.STEP_SECONDS

    /** Whether the burst has been started and [DURATION] seconds have not yet passed at [step]. */
    fun isActive(step: Long): Boolean = started && step - startStep in 0 until Rules.HOLE_IN_ONE_STEPS

    /** The sparks [age] seconds after the start; none after [DURATION]. */
    fun sparksAt(age: Double): List<Spark> {
        if (age < 0 || age >= DURATION) return emptyList()
        val alpha = ((DURATION - age) / FADE).coerceIn(0.0, 1.0)
        val travel = (1 - exp(-DRAG * age)) / DRAG
        return seeds.map {
            val position = origin + it.direction * (it.speed * travel) + Vec(0.0, GRAVITY * age * age / 2)
            Spark(position, it.size, alpha, it.color)
        }
    }

    fun paint(
        gc: GraphicsContext,
        placement: FieldPlacement,
        step: Long,
    ) {
        if (!isActive(step)) return
        val age = ageSeconds(step)
        gc.inField(placement) {
            sparksAt(age).forEach {
                globalAlpha = it.alpha
                fill = it.color
                fillCircle(it.position, it.size)
            }
            val fade = minOf(age / 0.2, (DURATION - age) / 0.3, 1.0).coerceIn(0.0, 1.0)
            globalAlpha = fade
            font = MiniGolfPalette.font(16.0)
            textAlign = TextAlignment.CENTER
            textBaseline = VPos.CENTER
            val x = origin.x.coerceIn(75.0, Rules.FIELD_WIDTH - 75.0)
            val y = (origin.y - 26.0 - age * 6.0).coerceAtLeast(Rules.HUD_HEIGHT + 12.0)
            stroke = Color.color(0.0, 0.0, 0.0, 0.7)
            lineWidth = 3.0
            strokeText(TEXT, x, y)
            fill = MiniGolfPalette.ACCENT
            fillText(TEXT, x, y)
            globalAlpha = 1.0
        }
    }

    companion object {
        const val DURATION = Rules.HOLE_IN_ONE_STEPS * Rules.STEP_SECONDS
        const val TEXT = "Hole in one!"
        private const val FADE = 0.5
        private const val COUNT = 48
        private const val DRAG = 2.2
        private const val GRAVITY = 40.0
        private val COLORS =
            listOf(
                Color.web("#ffd35c"),
                Color.web("#ffffff"),
                Color.web("#ff7a6b"),
                Color.web("#6be3ff"),
                Color.web("#9bff7a"),
            )
    }
}
