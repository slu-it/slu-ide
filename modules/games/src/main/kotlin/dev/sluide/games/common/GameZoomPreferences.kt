package dev.sluide.games.common

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.projectdata.ProjectDataStore
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.Executor

/**
 * The zoom of every game (ADR 0155), user-wide: in the top-level field `gameZoom` of `shared.json`, game id → factor,
 * e.g. `{"minesweeper": 0.75}`. Reading and writing (read once, delayed writes, flush on [destroy]) are those of
 * [StoredField]. A missing, non-numeric or non-finite value reads as 1.0 (Fit), one out of the game's range as its
 * nearest bound; other keys of `gameZoom` stay as they are. Thread-safe.
 *
 * Game ids: `minesweeper`, `wordGuess`, `doubles`, `sudoku`, `solitaire`, `pipePuzzle`, `orbitGuard`, `blockBreaker`,
 * `miniGolf`.
 */
@Component
class GameZoomPreferences internal constructor(
    store: ProjectDataStore,
    writeDelay: Duration,
    writer: Executor,
) : DisposableBean {
    @Autowired
    constructor(
        store: ProjectDataStore,
    ) : this(store, Duration.ofMillis(300), NamedThreads.single("sluide-game-zoom-prefs"))

    private val stored =
        StoredField(
            store = store,
            fieldName = FIELD,
            gameName = "Game zoom",
            writeDelay = writeDelay,
            writer = writer,
            read = ::read,
            write = ::write,
        )

    /** The zoom of [game], within [range]; 1.0 unless saved otherwise. */
    internal fun zoom(
        game: String,
        range: ZoomRange,
    ): Double = range.clamp(stored.value[game] ?: 1.0)

    /** Remembers [zoom] for [game]; a value that is no number is ignored. */
    internal fun setZoom(
        game: String,
        zoom: Double,
    ) {
        if (zoom.isFinite()) stored.change { it + (game to zoom) }
    }

    /** The app quits: the writer is a daemon thread, so pending values are written now; later changes write at once. */
    override fun destroy() = stored.destroy()

    private fun read(root: ObjectNode?): Map<String, Double> =
        root
            ?.properties()
            ?.mapNotNull { (game, value) ->
                value
                    .takeIf { it.isNumber }
                    ?.asDouble()
                    ?.takeIf { it.isFinite() }
                    ?.let { game to it }
            }?.toMap()
            ?: emptyMap()

    private fun write(
        values: Map<String, Double>,
        node: ObjectNode,
    ) {
        values.forEach { (game, zoom) -> node.put(game, zoom) }
    }

    private companion object {
        const val FIELD = "gameZoom"
    }
}
