package dev.sluide.converting.ui

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.OptionValues
import dev.sluide.converting.model.ConvertingModel
import javafx.geometry.Pos
import javafx.scene.control.CheckBox
import javafx.scene.control.ChoiceBox
import javafx.scene.control.Label
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox

/** The control of an option: a check box for a toggle, a labelled choice box for a choice. */
internal sealed interface OptionControl {
    val id: String

    data class Check(
        override val id: String,
        val label: String,
        val selected: Boolean,
    ) : OptionControl

    data class Select(
        override val id: String,
        val label: String,
        val choices: List<String>,
        val value: String,
    ) : OptionControl
}

/** The controls of [options] with their current [values]. */
internal fun optionControls(
    options: List<ConversionOption>,
    values: OptionValues,
): List<OptionControl> =
    options.map { option ->
        when (option) {
            is ConversionOption.Toggle -> {
                OptionControl.Check(option.id, option.label, values.flag(option))
            }

            is ConversionOption.Choice -> {
                OptionControl.Select(
                    option.id,
                    option.label,
                    option.choices,
                    values.choice(option),
                )
            }
        }
    }

/**
 * The options of the source's and the target's format; rebuilt when the set of options changes, otherwise only their
 * values follow the model. Every control sets its option in the model.
 */
internal class OptionsBar(
    private val model: ConvertingModel,
) {
    private var shown: List<ConversionOption>? = null
    private val syncs = ArrayList<(OptionControl) -> Unit>()
    private var updating = false

    val node: FlowPane =
        FlowPane(16.0, 6.0).apply {
            styleClass += "converting-options"
            alignment = Pos.CENTER_LEFT
        }

    init {
        refresh()
    }

    fun refresh() {
        val options = model.visibleOptions()
        val controls = optionControls(options, model.options)
        updating = true
        try {
            if (options != shown) {
                shown = options
                build(controls)
            }
            controls.zip(syncs).forEach { (control, sync) -> sync(control) }
        } finally {
            updating = false
        }
    }

    private fun build(controls: List<OptionControl>) {
        syncs.clear()
        node.children.clear()
        for (control in controls) {
            when (control) {
                is OptionControl.Check -> {
                    val box = CheckBox(control.label).apply { id = "converting-option-${control.id}" }
                    box.selectedProperty().subscribe { selected ->
                        if (!updating) model.setOption(control.id, selected)
                    }
                    syncs += { box.isSelected = (it as OptionControl.Check).selected }
                    node.children += box
                }

                is OptionControl.Select -> {
                    val box =
                        ChoiceBox<String>().apply {
                            id = "converting-option-${control.id}"
                            items.setAll(control.choices)
                        }
                    box.valueProperty().subscribe { value ->
                        if (!updating && value != null) model.setOption(control.id, value)
                    }
                    syncs += { box.value = (it as OptionControl.Select).value }
                    node.children += HBox(6.0, Label(control.label), box).apply { alignment = Pos.CENTER_LEFT }
                }
            }
        }
    }
}
