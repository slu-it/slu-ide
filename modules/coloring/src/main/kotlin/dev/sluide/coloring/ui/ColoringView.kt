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
 * The content of the Coloring tab: the current color's sections in a column at the left, the tools that compare or
 * derive colors in tabs at the right. The colors live in the window's state, which outlives the tab: every section and
 * pane releases its listeners and bindings on it in `dispose()`, and [dispose] calls them all, so a new section must
 * be added there.
 */
internal class ColoringView(
    state: ColoringState,
    savedColors: SavedColors,
    projectFolder: ObservableValue<Path?>,
) {
    private val input = InputSection(state)
    private val picker = PickerSection(state)
    private val formats = FormatsSection(state)
    private val palette = PaletteSection(state, savedColors, projectFolder)
    private val contrast = ContrastPane(state)
    private val harmonies = HarmoniesPane(state)
    private val mix = MixPane(state)
    private val gradient = GradientPane(state)
    private val vision = VisionPane(state)

    /** The tool tabs at the right, in order. */
    val toolTabs: TabPane =
        TabPane(
            tool("Contrast", contrast.node),
            tool("Shades & Harmonies", harmonies.node),
            tool("Mix", mix.node),
            tool("Gradient", gradient.node),
            tool("Vision", vision.node),
        ).apply { tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE }

    val node: Node =
        BorderPane().apply {
            styleClass += "coloring"
            stylesheets +=
                checkNotNull(ColoringView::class.java.getResource("/dev/sluide/coloring/coloring.css"))
                    .toExternalForm()
            left =
                ScrollPane(
                    VBox(
                        12.0,
                        input.node,
                        picker.node,
                        formats.node,
                        palette.node,
                    ).apply {
                        styleClass += "coloring-column"
                        padding = Insets(12.0)
                    },
                ).apply {
                    isFitToWidth = true
                    hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                    prefWidth = COLUMN_WIDTH
                    minWidth = COLUMN_WIDTH
                    maxWidth = COLUMN_WIDTH
                }
            center = toolTabs
            // Focus goes to the color input field when the tab is shown, as in a terminal.
            sceneProperty().subscribe { scene -> if (scene != null) Platform.runLater(::focus) }
        }

    private fun tool(
        title: String,
        content: Node,
    ) = Tab(title, content)

    /** Gives the focus to the first input of the tool: the color input field. */
    fun focus() = input.focus()

    /** Releases the listeners of all sections and panes on the window's state. */
    fun dispose() {
        input.dispose()
        picker.dispose()
        formats.dispose()
        palette.dispose()
        contrast.dispose()
        harmonies.dispose()
        mix.dispose()
        gradient.dispose()
        vision.dispose()
    }

    private companion object {
        const val COLUMN_WIDTH = 380.0
    }
}
