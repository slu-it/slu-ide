package dev.sluide.converting.ui

import dev.sluide.converting.model.ConvertingModel
import dev.sluide.converting.model.Side
import javafx.beans.value.ObservableValue
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.SplitPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.stage.Window
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignS
import java.io.File
import java.nio.file.Path

/**
 * The Converting tool: Swap and the options at the top, the two sides below. It only shows [model] and forwards
 * edits; the view that shows a model owns its [ConvertingModel.onChange] until [dispose]. The editors use the font
 * of [editorCss] (a CSS style).
 */
internal class ConvertingView(
    private val model: ConvertingModel,
    editorCss: ObservableValue<String>,
    chooseFile: (Window?) -> Path?,
    chooseSaveFile: (Window?, String) -> Path?,
    openScratch: ((extension: String, text: String) -> Unit)?,
) {
    private val options = OptionsBar(model)
    private val sides =
        Side.entries.associateWith { side ->
            ConvertingSide(model, side, editorCss, chooseFile, chooseSaveFile, openScratch)
        }

    val node: Node =
        BorderPane().apply {
            styleClass += "converting"
            stylesheets +=
                checkNotNull(
                    ConvertingView::class.java.getResource("/dev/sluide/converting/converting.css"),
                ).toExternalForm()
            top =
                HBox(
                    16.0,
                    Button("Swap", FontIcon(MaterialDesignS.SWAP_HORIZONTAL)).apply {
                        id = "converting-swap"
                        setOnAction { model.swap() }
                    },
                    options.node.also { HBox.setHgrow(it, Priority.ALWAYS) },
                ).apply {
                    styleClass += "converting-top"
                    padding = Insets(8.0)
                    alignment = Pos.CENTER_LEFT
                }
            center =
                SplitPane(sides.getValue(Side.LEFT).node, sides.getValue(Side.RIGHT).node).apply {
                    setDividerPositions(0.5)
                }
        }

    private val installed: () -> Unit = ::refresh

    init {
        model.onChange = installed
    }

    /** Opens the first of [files] on [side], as a drop does. */
    internal fun filesDropped(
        side: Side,
        files: List<File>,
    ) = sides.getValue(side).filesDropped(files)

    private fun refresh() {
        options.refresh()
        sides.values.forEach(ConvertingSide::refresh)
    }

    /** The model stops notifying this view, unless a newer view has taken it over; the editors leave the font. */
    fun dispose() {
        if (model.onChange === installed) model.onChange = {}
        sides.values.forEach(ConvertingSide::dispose)
    }
}
