package dev.sluide.pdf.view.side

import dev.sluide.pdf.engine.OutlineItem
import dev.sluide.pdf.view.PdfFileView
import javafx.scene.control.Control
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.control.skin.VirtualFlow
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignF

/** The tabs of the side panel. */
internal enum class SideTab { OUTLINE, THUMBNAILS }

/** How the side panel starts: the tab selected and whether the panel opens. */
internal data class SideStart(
    val tab: SideTab,
    val open: Boolean,
)

/**
 * How the side panel starts for a document with [outline]: open on Outline if it has one, else with Thumbnails selected
 * and not opened (it stays closed, unless something opened it before).
 */
internal fun sideStart(outline: List<OutlineItem>): SideStart =
    if (outline.isEmpty()) SideStart(SideTab.THUMBNAILS, open = false) else SideStart(SideTab.OUTLINE, open = true)

/**
 * The left panel of a PDF view (ADR 0192): the Outline ([OutlinePanel]) and Thumbnails ([ThumbnailsPanel]) tabs, shown
 * and hidden by a toggle in the toolbar's side panel slot. It starts as [sideStart] says once the first document
 * opened; after that the tab and whether the panel is open are the user's, for the tab's life (reloads keep them,
 * nothing is stored). FX thread only.
 */
internal class SidePanel private constructor(
    private val view: PdfFileView,
) {
    private val toggle =
        ToggleButton(null, FontIcon(MaterialDesignF.FORMAT_LIST_BULLETED)).apply {
            styleClass += "pdf-button"
            isFocusTraversable = false
            tooltip = Tooltip("Side Panel")
        }
    private val outline = OutlinePanel(view)
    private val thumbnails = ThumbnailsPanel(view)
    private val outlineTab = Tab("Outline", outline.node)
    private val thumbnailsTab = Tab("Thumbnails", thumbnails.node)
    private val tabs =
        TabPane(outlineTab, thumbnailsTab).apply {
            styleClass += "pdf-side-panel"
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
        }

    /** Whether the panel took its start state ([sideStart]) yet. */
    private var started = false

    init {
        view.toolbar.sidePanelSlot.children += toggle
        view.split.left.children += tabs
        toggle.selectedProperty().subscribe { selected -> view.split.showLeft(selected) }
        view.split.leftShown.subscribe { shown ->
            toggle.isSelected = shown
            updateThumbnails()
        }
        tabs.selectionModel.selectedItemProperty().subscribe { _ -> updateThumbnails() }
        view.info.subscribe { info -> if (info != null && !started) start(sideStart(info.outline)) }
    }

    private fun start(start: SideStart) {
        started = true
        tabs.selectionModel.select(if (start.tab == SideTab.OUTLINE) outlineTab else thumbnailsTab)
        if (start.open) toggle.isSelected = true
    }

    private fun updateThumbnails() =
        thumbnails.active(view.split.leftShown.get() && tabs.selectionModel.selectedItem === thumbnailsTab)

    companion object {
        /** Adds the side panel and its toggle to [view]. */
        fun install(view: PdfFileView): SidePanel = SidePanel(view)
    }
}

/** Scrolls the list of [control] the least to show [row] (none for -1, nothing before its skin made the list). */
internal fun showRow(
    control: Control,
    row: Int,
) {
    if (row < 0) return
    (control.lookup(".virtual-flow") as? VirtualFlow<*>)?.scrollTo(row)
}
