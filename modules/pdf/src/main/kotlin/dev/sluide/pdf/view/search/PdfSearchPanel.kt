package dev.sluide.pdf.view.search

import dev.sluide.pdf.view.PdfFileView
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignM

/**
 * Adds the search to a PDF view (ADR 0192): a toolbar toggle and Shortcut+F anywhere in the view open the
 * [SearchPanel] in the view's right panel; the [SearchFlow] behind it uses the view's hooks.
 */
internal object PdfSearchPanel {
    fun install(view: PdfFileView) {
        val toggle =
            ToggleButton(null, FontIcon(MaterialDesignM.MAGNIFY)).apply {
                styleClass += "pdf-button"
                isFocusTraversable = false
                tooltip = Tooltip("Search (Shortcut+F)")
            }
        lateinit var flow: SearchFlow
        val panel =
            SearchPanel(
                onQueryChanged = { flow.clear() },
                onSearch = { query -> flow.search(query) },
                onChoose = { hit -> flow.choose(hit) },
                onClose = {
                    toggle.isSelected = false
                    view.pages.requestFocus()
                },
            )
        flow =
            SearchFlow(
                SearchHost(
                    pageCount = { view.info.value?.pageCount },
                    plainTextOf = view.texts::plainTextOf,
                    textOf = view.texts::textOf,
                    shownPages = { view.shownPages.value },
                    overlays = view.overlays,
                    scrollIntoView = view::scrollIntoView,
                    showStatus = panel::showStatus,
                    showRows = panel::showRows,
                ),
            )
        view.toolbar.searchSlot.children += toggle
        view.split.right.children += panel.node
        toggle.selectedProperty().subscribe { selected ->
            view.split.showRight(selected)
            if (selected) {
                panel.focusField()
                flow.search(panel.query)
            } else {
                panel.closed()
                flow.clear()
            }
        }
        view.info.subscribe { _ -> flow.documentChanged() }
        view.shownPages.subscribe { _ -> flow.shownPagesChanged() }
        // A reload whose info equals the old one (same page sizes and outline) does not change info.
        view.onReload += flow::documentChanged
        view.onDispose += flow::dispose
        // On the root, so that it works wherever the focus is in the view, the page field included.
        view.node.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (FIND.match(event)) {
                toggle.isSelected = true
                panel.focusField()
                event.consume()
            }
        }
    }

    private val FIND = KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN)
}
