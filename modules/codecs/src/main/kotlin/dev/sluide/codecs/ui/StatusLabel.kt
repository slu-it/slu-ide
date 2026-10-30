package dev.sluide.codecs.ui

import dev.sluide.codecs.conversion.Status
import javafx.scene.Cursor
import javafx.scene.control.Label

/**
 * The line under a side: [Status.Info] muted, [Status.Error] in the error style with the position as "(character N)".
 * A click on an error with a position calls [onErrorClick] with the 0-based index.
 */
internal class StatusLabel(
    private val onErrorClick: (Int) -> Unit,
) : Label() {
    private var position: Int? = null

    init {
        styleClass += "codecs-status"
        isWrapText = true
        setOnMouseClicked { position?.let(onErrorClick) }
    }

    fun show(
        status: Status,
        warning: Boolean = false,
    ) {
        styleClass.removeAll("codecs-error", "codecs-warning")
        position = null
        when (status) {
            is Status.Info -> {
                text = status.text
                if (warning) styleClass += "codecs-warning"
            }

            is Status.Error -> {
                position = status.position
                text = status.position?.let { "${status.message} (character ${it + 1})" } ?: status.message
                styleClass += "codecs-error"
            }
        }
        cursor = if (position != null) Cursor.HAND else Cursor.DEFAULT
    }
}
