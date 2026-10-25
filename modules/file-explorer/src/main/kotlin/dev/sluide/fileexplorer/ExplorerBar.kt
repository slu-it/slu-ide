package dev.sluide.fileexplorer

import javafx.beans.binding.Bindings
import javafx.beans.binding.BooleanBinding
import javafx.beans.value.ObservableValue
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import org.kordamp.ikonli.materialdesign2.MaterialDesignR
import java.nio.file.Path

/**
 * The slim row of icon buttons above the explorer's tree: Select Opened File, Collapse All, Refresh and the Compact
 * Middle Folders toggle, which follows and sets the explorer's setting (shared by all windows). The window
 * tells it which file is opened ([openedFile]) and what selecting it means ([selectOpened]); the explorer module
 * does not know the editor. [selectOpenedKey] is the platform's text of the shortcut, shown in the tooltip.
 */
class ExplorerBar(
    private val explorer: FileExplorerView,
    openedFile: ObservableValue<Path?>,
    selectOpened: () -> Unit,
    selectOpenedKey: String,
    refreshKey: String = "F5",
) : HBox(2.0) {
    /** Whether there is an opened file that a root holds; false while the tree is empty or the file is elsewhere. */
    val canSelectOpened: BooleanBinding =
        Bindings.createBooleanBinding(
            { openedFile.value?.let(explorer::canReveal) == true },
            openedFile,
            explorer.rootProperty(),
        )

    /**
     * Reveals the opened file, which takes the focus into the tree ([selectOpened]); disabled unless
     * [canSelectOpened].
     */
    val selectOpenedButton: Button =
        button(
            MaterialDesignC.CROSSHAIRS_GPS,
            "Select Opened File ($selectOpenedKey)",
            "explorer-select-opened",
            selectOpened,
        ).apply {
            disableProperty().bind(canSelectOpened.not())
        }

    val collapseAllButton: Button =
        button(MaterialDesignC.COLLAPSE_ALL, "Collapse All", "explorer-collapse-all") { explorer.collapseAll() }

    val refreshButton: Button =
        button(MaterialDesignR.REFRESH, "Refresh ($refreshKey)", "explorer-refresh") { explorer.refreshAll() }

    /** Shows chains of folders holding only a folder as one row (ADR 0096); on while the setting is. */
    val compactToggle: ToggleButton =
        ToggleButton(null, FontIcon(MaterialDesignF.FILE_TREE_OUTLINE)).apply {
            id = "explorer-compact-folders"
            styleClass += "explorer-bar-button"
            tooltip = Tooltip("Compact Middle Folders")
            isFocusTraversable = false
            selectedProperty().bindBidirectional(explorer.compactFolders)
        }

    init {
        styleClass += "explorer-bar"
        alignment = Pos.CENTER_LEFT
        padding = Insets(2.0, 4.0, 2.0, 4.0)
        children.setAll(selectOpenedButton, collapseAllButton, refreshButton, compactToggle)
        stylesheets += checkNotNull(ExplorerBar::class.java.getResource("file-explorer.css")).toExternalForm()
    }

    private fun button(
        icon: Ikon,
        tip: String,
        id: String,
        action: () -> Unit,
    ): Button =
        Button(null, FontIcon(icon)).apply {
            this.id = id
            styleClass += "explorer-bar-button"
            tooltip = Tooltip(tip)
            isFocusTraversable = false
            setOnAction { action() }
        }
}
