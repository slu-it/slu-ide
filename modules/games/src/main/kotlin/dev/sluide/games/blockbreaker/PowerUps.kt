package dev.sluide.games.blockbreaker

internal enum class PowerUp { WIDE, MULTI, SLOW, LIFE }

/** A falling capsule; x and y are left/top. */
internal class Capsule(
    var x: Double,
    var y: Double,
    val kind: PowerUp,
) {
    fun fall() {
        y += 1.0
    }

    fun overlapsPaddle(
        paddleX: Double,
        paddleWidth: Double,
    ): Boolean =
        x < paddleX + paddleWidth &&
            paddleX < x + Rules.CAPSULE_WIDTH &&
            y < Rules.PADDLE_TOP + Rules.PADDLE_HEIGHT &&
            Rules.PADDLE_TOP < y + Rules.CAPSULE_HEIGHT

    /** Top has passed the bottom of the field. */
    val isOut: Boolean get() = y > Rules.FIELD_BOTTOM

    fun state() = CapsuleState(x, y, kind)

    companion object {
        /** The kind for a roll in [0, 1): WIDE < 0.35, MULTI < 0.65, SLOW < 0.90, else LIFE. */
        fun kindFor(roll: Double): PowerUp =
            when {
                roll < 0.35 -> PowerUp.WIDE
                roll < 0.65 -> PowerUp.MULTI
                roll < 0.90 -> PowerUp.SLOW
                else -> PowerUp.LIFE
            }

        /** A capsule centred on the brick at ([column], [row]). */
        fun dropFrom(
            column: Int,
            row: Int,
            kind: PowerUp,
        ) = Capsule(
            (Rules.PLAY_LEFT + column * Rules.BRICK_WIDTH + (Rules.BRICK_WIDTH - Rules.CAPSULE_WIDTH) / 2).toDouble(),
            (Rules.BRICK_TOP + row * Rules.BRICK_HEIGHT + (Rules.BRICK_HEIGHT - Rules.CAPSULE_HEIGHT) / 2).toDouble(),
            kind,
        )
    }
}
