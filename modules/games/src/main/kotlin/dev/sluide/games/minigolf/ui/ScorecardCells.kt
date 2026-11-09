package dev.sluide.games.minigolf.ui

import dev.sluide.games.minigolf.ScoreText

/** How a hole went compared with its par. */
internal enum class HoleResult { UNDER_PAR, AT_PAR, OVER_PAR, NOT_PLAYED }

/** The texts of one scorecard column; [result] colours [you] and [toPar]. */
internal data class ScorecardCell(
    val hole: String,
    val par: String,
    val you: String,
    val toPar: String,
    val result: HoleResult,
    val best: String,
)

/** The pure part of the scorecard: what each cell says and how the columns split into rows. */
internal object ScorecardCells {
    const val COLUMNS_PER_ROW = 9
    private const val DASH = "–"

    fun result(
        par: Int,
        strokes: Int?,
    ): HoleResult =
        when {
            strokes == null -> HoleResult.NOT_PLAYED
            strokes < par -> HoleResult.UNDER_PAR
            strokes == par -> HoleResult.AT_PAR
            else -> HoleResult.OVER_PAR
        }

    /** One cell per hole of [pars]; [scores] and [bests] hold null for a hole that was not played (or has no best). */
    fun cells(
        pars: List<Int>,
        scores: List<Int?>,
        bests: List<Int?>,
    ): List<ScorecardCell> =
        pars.indices.map { i ->
            val strokes = scores.getOrNull(i)
            ScorecardCell(
                hole = (i + 1).toString(),
                par = pars[i].toString(),
                you = strokes?.toString() ?: DASH,
                toPar = strokes?.let { ScoreText.toPar(it - pars[i]) } ?: DASH,
                result = result(pars[i], strokes),
                best = bests.getOrNull(i)?.toString() ?: DASH,
            )
        }

    /** The column ranges of the rows: rows of [COLUMNS_PER_ROW], the last one shorter. */
    fun rows(count: Int): List<IntRange> {
        val starts = 0 until count step COLUMNS_PER_ROW
        return starts.map { it until minOf(it + COLUMNS_PER_ROW, count) }
    }

    /** The strokes, the par and the strokes relative to par over the holes that were played. */
    fun totals(
        pars: List<Int>,
        scores: List<Int?>,
    ): Totals {
        val played = pars.indices.filter { scores.getOrNull(it) != null }
        val strokes = played.sumOf { scores[it] ?: 0 }
        val parSoFar = played.sumOf { pars[it] }
        return Totals(strokes, parSoFar, strokes - parSoFar)
    }

    /** "Total 20 (+1)". */
    fun totalLine(totals: Totals): String = "Total ${totals.strokes} (${ScoreText.toPar(totals.toPar)})"

    /** "Round over · Total 52 (+1)", with " — new best!" when [newBest]. */
    fun finalLine(
        totals: Totals,
        newBest: Boolean,
    ): String = "Round over · ${totalLine(totals)}" + if (newBest) " — new best!" else ""
}

/** The totals of the holes played: [strokes], their [parSoFar] and the difference [toPar]. */
internal data class Totals(
    val strokes: Int,
    val parSoFar: Int,
    val toPar: Int,
)
