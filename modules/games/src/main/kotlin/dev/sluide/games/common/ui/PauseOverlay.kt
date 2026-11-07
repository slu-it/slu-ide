package dev.sluide.games.common.ui

import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.text.TextAlignment

/** The "Paused" cover; [node] is stacked over the board. A click calls [onClick]. FX thread. */
internal class PauseOverlay(
    onClick: () -> Unit,
) {
    private val hint =
        Label().apply {
            styleClass += "game-paused-hint"
            // Wraps instead of being clipped over a small board.
            isWrapText = true
            textAlignment = TextAlignment.CENTER
        }

    val node: Region =
        VBox(8.0, Label("Paused").apply { styleClass += "game-paused-title" }, hint).apply {
            styleClass += "game-overlay"
            stylesheets +=
                checkNotNull(PauseOverlay::class.java.getResource("/dev/sluide/games/common/pause-overlay.css"))
                    .toExternalForm()
            alignment = Pos.CENTER
            isVisible = false
            setOnMouseClicked { onClick() }
        }

    fun show(
        paused: Boolean,
        manual: Boolean,
    ) {
        show(paused, if (manual) "Press P or click to resume" else "Resumes when the tab is shown again")
    }

    /** Shows or hides the cover with [hint] as its text. */
    fun show(
        paused: Boolean,
        hint: String,
    ) {
        node.isVisible = paused
        this.hint.text = hint
    }
}
