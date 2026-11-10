package dev.sluide.games.common.ui

import javafx.scene.layout.Region

/**
 * The space a zoomed game content fits into at Fit (ADR 0155): the inside of [area], the `ScrollPane` around the
 * content, without its scroll bars. [onChange] is called when the area's size or insets change, so that the content
 * lays out again.
 *
 * The pattern for a content in a scroll pane that fits it to the viewport (`isFitToWidth`, `isFitToHeight`): such a
 * scroll pane sizes the content to the viewport and ignores its pref size, so a larger pref size never scrolls.
 * - Compute the Fit size from [innerWidth] and [innerHeight], not from the content's own size, which grows above Fit
 *   and would feed back into the Fit size.
 * - Make the content's minimum size the zoomed size: above Fit it outgrows the viewport, and the scroll pane scrolls.
 * - Below Fit the zoomed size is smaller than the inside, so the content still fits and needs no scroll bars.
 */
internal class FitArea(
    private val area: Region,
    onChange: () -> Unit,
) {
    init {
        area.layoutBoundsProperty().addListener { _, _, _ -> onChange() }
        area.insetsProperty().addListener { _, _, _ -> onChange() }
    }

    val innerWidth: Double get() = area.width - area.snappedLeftInset() - area.snappedRightInset()

    val innerHeight: Double get() = area.height - area.snappedTopInset() - area.snappedBottomInset()
}
