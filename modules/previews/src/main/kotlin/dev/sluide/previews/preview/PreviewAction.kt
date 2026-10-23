package dev.sluide.previews.preview

import javafx.beans.value.ObservableBooleanValue

/**
 * A button a [Preview] adds to its view's toolbar ([Preview.actions]), beside the Source / Split / Preview toggles and
 * only while the preview shows: [label] on it, [tooltip] on hover, greyed out while [disabled]; a click calls [run].
 * Everything on the FX thread.
 */
internal class PreviewAction(
    val label: String,
    val tooltip: String,
    val disabled: ObservableBooleanValue,
    val run: () -> Unit,
)
