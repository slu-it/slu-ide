package dev.sluide.codecs.ui

import dev.sluide.codecs.codec.CodecGroup
import dev.sluide.codecs.codec.CodecId
import dev.sluide.codecs.conversion.ConversionModel
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.VBox

/** The codecs in groups; the toggle of `settings.codec` is selected, a click on another one switches the codec. */
internal class CodecList(
    private val model: ConversionModel,
) {
    private val group = ToggleGroup()
    private val buttons = CodecId.entries.associateWith { button(it) }
    private var updating = false

    val node: VBox =
        VBox(2.0).apply {
            styleClass += "codecs-list"
            for (codecGroup in CodecGroup.entries) {
                children += Label(codecGroup.title).apply { styleClass += "codecs-group" }
                CodecId.entries.filter { it.group == codecGroup }.forEach { children += buttons.getValue(it) }
            }
        }

    init {
        group.selectedToggleProperty().subscribe { toggle ->
            if (updating) return@subscribe
            val id = toggle?.userData as? CodecId
            // A click on the selected toggle would clear it; the selection always follows the model.
            if (id == null) refresh() else model.updateSettings { it.copy(codec = id) }
        }
        refresh()
    }

    private fun button(id: CodecId) =
        ToggleButton(id.title).apply {
            this.id = "codec-${id.name.lowercase()}"
            userData = id
            toggleGroup = group
            styleClass += "codecs-codec"
            maxWidth = Double.MAX_VALUE
        }

    fun refresh() {
        updating = true
        try {
            group.selectToggle(buttons.getValue(model.settings.codec))
        } finally {
            updating = false
        }
    }
}
