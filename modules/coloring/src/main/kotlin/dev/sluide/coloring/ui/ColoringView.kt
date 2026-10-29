package dev.sluide.coloring.ui

import dev.sluide.coloring.SavedColors
import javafx.application.Platform
import javafx.beans.value.ObservableValue
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox
import java.nio.file.Path

/**
 * The content of the Coloring tab (ADR 0111): at the left the picker for the slot being edited, in the center the
 * tools that compare or derive colors in tabs, at the right the inspector. Switching the tool tab edits the slot that
 * tab remembers. The colors live in the window's state, which outlives the tab: every section and pane releases its
 * listeners and bindings on it in `dispose()`, and [dispose] calls them all, so a new section must be added there.
 */
internal class ColoringView(
    state: ColoringState,
    savedColors: SavedColors,
    projectFolder: ObservableValue<Path?>,
) {
    private val input = InputSection(state)
    private val picker = PickerSection(state)
    private val palette = PaletteSection(state, savedColors, projectFolder)
    private val inspector = InspectorSection(state, savedColors, projectFolder)
    private val contrast = ContrastPane(state)
    private val harmonies = HarmoniesPane(state)
    private val mix = MixPane(state)
    private val gradient = GradientPane(state)
    private val vision = VisionPane(state)

    /** The tool tabs in the center, in order. */
    val toolTabs: TabPane =
        TabPane(
            tool(ColoringState.CONTRAST, contrast.node),
            tool(ColoringState.HARMONIES, harmonies.node),
            tool(ColoringState.MIX, mix.node),
            tool(ColoringState.GRADIENT, gradient.node),
            tool(ColoringState.VISION, vision.node),
        ).apply { tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE }

    private val tabSubscription =
        toolTabs.selectionModel.selectedItemProperty().subscribe { tab -> tab?.let { state.editIn(it.text) } }

    val node: Node =
        BorderPane().apply {
            styleClass += "coloring"
            stylesheets +=
                checkNotNull(ColoringView::class.java.getResource("/dev/sluide/coloring/coloring.css"))
                    .toExternalForm()
            left = column("coloring-left", COLUMN_WIDTH, input.node, picker.node, palette.node)
            center = toolTabs
            right = column("coloring-right", INSPECTOR_WIDTH, inspector.node)
            // Focus goes to the color input field when the tab is shown, as in a terminal.
            sceneProperty().subscribe { scene -> if (scene != null) Platform.runLater(::focus) }
        }

    private fun column(
        id: String,
        width: Double,
        vararg content: Node,
    ) = ScrollPane(
        VBox(12.0, *content).apply {
            styleClass += "coloring-column"
            padding = Insets(12.0)
        },
    ).apply {
        this.id = id
        isFitToWidth = true
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        prefWidth = width
        minWidth = width
        maxWidth = width
    }

    /** A tool tab; its content scrolls vertically, as a pane can be taller than a short window. */
    private fun tool(
        title: String,
        content: Node,
    ) = Tab(
        title,
        ScrollPane(content).apply {
            styleClass += "coloring-tool"
            isFitToWidth = true
        },
    )

    /** Gives the focus to the first input of the tool: the color input field. */
    fun focus() = input.focus()

    /** Releases the listeners of all sections and panes on the window's state. */
    fun dispose() {
        tabSubscription.unsubscribe()
        input.dispose()
        picker.dispose()
        palette.dispose()
        inspector.dispose()
        contrast.dispose()
        harmonies.dispose()
        mix.dispose()
        gradient.dispose()
        vision.dispose()
    }

    private companion object {
        const val COLUMN_WIDTH = 380.0
        const val INSPECTOR_WIDTH = 320.0
    }
}
