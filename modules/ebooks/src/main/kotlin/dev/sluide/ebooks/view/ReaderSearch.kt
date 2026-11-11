package dev.sluide.ebooks.view

import javafx.application.Platform
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.util.Duration
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignM

/** Adds the Search of the whole book to a reader: a toolbar toggle, Shortcut+F and the [SearchPanel] in the right pane. */
internal object ReaderSearch {
    private val findKeys = KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN)

    fun attach(
        view: EpubFileView,
        debounce: Duration = Duration.millis(300.0),
    ): SearchPanel {
        val toggle =
            ToggleButton(null, FontIcon(MaterialDesignM.MAGNIFY)).apply {
                isFocusTraversable = false
                tooltip = Tooltip("Search (Shortcut+F)")
                styleClass += "ebook-button"
            }
        val panel =
            SearchPanel(view, onClose = {
                toggle.isSelected = false
                view.webView.requestFocus()
            }, debounce = debounce)
        view.searchSlot.children += toggle
        toggle.selectedProperty().subscribe { selected ->
            val shown = view.rightPane.children.contains(panel.node)
            if (selected && !shown) {
                view.rightPane.children += panel.node
                panel.focusField()
                panel.refresh()
            } else if (!selected && shown) {
                view.rightPane.children -= panel.node
                panel.closed()
            }
        }
        view.rightPane.children.subscribe {
            val shown = view.rightPane.children.contains(panel.node)
            if (toggle.isSelected != shown) toggle.isSelected = shown
        }
        view.addKeyFilter { event ->
            val find = findKeys.match(event)
            if (find && event.eventType == KeyEvent.KEY_PRESSED) {
                toggle.isSelected = true
                panel.focusField()
                Platform.runLater { panel.focusField() }
            }
            find
        }
        view.onPageLoaded += { panel.pageLoaded() }
        view.onDispose += { panel.dispose() }
        return panel
    }
}
