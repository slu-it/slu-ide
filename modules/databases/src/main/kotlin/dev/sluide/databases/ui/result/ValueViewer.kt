package dev.sluide.databases.ui.result

import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.Highlighters
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Window
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** What the value viewer shows: [text] is the complete value of [column]; [editable] adds Apply. */
internal data class ValueRequest(
    val column: String,
    val text: String,
    val editable: Boolean,
)

/** The language a value is highlighted in, by looking at its text. */
internal object ValueLanguage {
    private val json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

    /**
     * "json" for an object or array that parses, "xml" for a document starting with `<` that parses, else null; texts
     * longer than the highlight limit (1 MiB) are not looked at.
     */
    fun of(text: String): String? {
        if (text.length > CodeEditors.HIGHLIGHT_LIMIT) return null
        val t = text.trim()
        return when {
            (t.startsWith("{") || t.startsWith("[")) && isJson(t) -> "json"
            t.startsWith("<") && isXml(t) -> "xml"
            else -> null
        }
    }

    private fun isJson(text: String): Boolean = runCatching { json.readTree(text) }.isSuccess

    private fun isXml(text: String): Boolean =
        try {
            val factory =
                XMLInputFactory.newFactory().apply {
                    setProperty(XMLInputFactory.SUPPORT_DTD, false)
                    setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                }
            val reader = factory.createXMLStreamReader(StringReader(text))
            var elements = 0
            while (reader.hasNext()) if (reader.next() == XMLStreamConstants.START_ELEMENT) elements++
            elements > 0
        } catch (_: Exception) {
            false
        }
}

/** The dialog of the value viewer (Shift+Enter in the grid). */
internal object ValueViewer {
    /**
     * Shows [request] and returns the text to apply, or null (Cancel, Close). Copy hands the complete text to [copy].
     * A read-only request is shown in a display that cannot be edited. Call on the FX thread.
     */
    fun show(
        request: ValueRequest,
        owner: Window?,
        copy: (String) -> Unit,
    ): String? {
        val highlighter = ValueLanguage.of(request.text)?.let { Highlighters.forLanguage(it) }
        val node: Node
        val currentText: () -> String
        if (request.editable) {
            val editor = CodeEditors.text(request.text, highlighter)
            node = editor.node
            currentText = { editor.text }
        } else {
            val display = CodeEditors.display().apply { show(request.text, highlighter) }
            node = display.node
            currentText = { display.text }
        }
        val copyButton = Button("Copy").apply { setOnAction { copy(currentText()) } }
        val apply = ButtonType("Apply", ButtonBar.ButtonData.OK_DONE)
        val dialog =
            Dialog<String?>().apply {
                if (owner != null) initOwner(owner)
                title = "Value of ${request.column}"
                headerText = null
                dialogPane.content =
                    VBox(6.0, node, copyButton).apply {
                        VBox.setVgrow(node, Priority.ALWAYS)
                        prefWidth = 640.0
                        prefHeight = 360.0
                    }
                dialogPane.buttonTypes +=
                    if (request.editable) listOf(apply, ButtonType.CANCEL) else listOf(ButtonType.CLOSE)
                isResizable = true
                setResultConverter { if (it == apply) currentText() else null }
            }
        return dialog.showAndWait().orElse(null)
    }
}
