package dev.sluide.pdf.view.side

import dev.sluide.pdf.engine.OutlineItem

/** The tabs of the side panel. */
internal enum class SideTab { OUTLINE, THUMBNAILS }

/** How the side panel starts: the tab selected and whether the panel is open. */
internal data class SideStart(
    val tab: SideTab,
    val open: Boolean,
)

/**
 * How the side panel starts for a document with [outline]: open on Outline if it has one, else closed with Thumbnails
 * selected.
 */
internal fun sideStart(outline: List<OutlineItem>): SideStart =
    if (outline.isEmpty()) SideStart(SideTab.THUMBNAILS, open = false) else SideStart(SideTab.OUTLINE, open = true)
