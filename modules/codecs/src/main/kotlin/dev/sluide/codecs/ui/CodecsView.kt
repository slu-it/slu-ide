package dev.sluide.codecs.ui

import dev.sluide.codecs.conversion.ConversionModel
import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.control.SplitPane
import javafx.scene.layout.BorderPane
import javafx.stage.Window
import java.io.File
import java.nio.file.Path

/**
 * The Codecs tool: the codec list, the options of the codec and the two sides. It only shows [model] and forwards
 * edits; the view that shows a model owns its [ConversionModel.onChange] until [dispose].
 */
internal class CodecsView(
    private val model: ConversionModel,
    chooseFile: (Window?) -> Path?,
    chooseSaveFile: (Window?, String) -> Path?,
) {
    private val list = CodecList(model)
    private val options = OptionsBar(model)
    private val textSide = TextSide(model, chooseFile, chooseSaveFile)
    private val encodedSide = EncodedSide(model)

    val node: Node =
        BorderPane().apply {
            styleClass += "codecs"
            stylesheets +=
                checkNotNull(CodecsView::class.java.getResource("/dev/sluide/codecs/codecs.css")).toExternalForm()
            left =
                ScrollPane(list.node).apply {
                    isFitToWidth = true
                    hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                }
            top = options.node
            center = SplitPane(textSide.node, encodedSide.node).apply { setDividerPositions(0.5) }
        }

    private val installed: () -> Unit = ::refresh

    init {
        model.onChange = installed
    }

    /** Opens the first of [files] on the text side. */
    internal fun filesDropped(files: List<File>) = textSide.filesDropped(files)

    private fun refresh() {
        list.refresh()
        options.refresh()
        textSide.refresh()
        encodedSide.refresh()
    }

    /** The model stops notifying this view, unless a newer view has taken it over. */
    fun dispose() {
        if (model.onChange === installed) model.onChange = {}
    }
}
