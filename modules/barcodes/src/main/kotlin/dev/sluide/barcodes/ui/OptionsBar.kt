package dev.sluide.barcodes.ui

import dev.sluide.barcodes.BarcodeOptionsStore
import dev.sluide.barcodes.engine.CodeFormat
import dev.sluide.barcodes.engine.GenerateOptions
import dev.sluide.barcodes.engine.QrEcc
import dev.sluide.barcodes.model.BarcodeModel
import dev.sluide.barcodes.model.quietZoneToKeep
import dev.sluide.fileviewers.WrappingToolbar
import javafx.event.ActionEvent
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ChoiceBox
import javafx.scene.control.ColorPicker
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.layout.HBox
import javafx.util.StringConverter

/**
 * The toolbar above both sides: the format picker with the options of that format, then the options of every format.
 * The controls show [model]; changes the user makes go to [BarcodeModel.setFormat] and [BarcodeModel.setOptions],
 * changes the app makes ([show]) do not, as [updating] is set meanwhile (a read image switching the picker is no
 * request for a generated code).
 */
internal class OptionsBar(
    private val model: BarcodeModel,
) {
    private var updating = false

    private val format =
        ComboBox<CodeFormat>().apply {
            id = "barcodes-format"
            items.setAll(CodeFormat.entries.filter { it.generatable })
            converter = titles { it.title }
            isFocusTraversable = false
        }
    private val qrEcc =
        ChoiceBox<QrEcc>().apply {
            id = "barcodes-qr-ecc"
            items.setAll(QrEcc.entries)
            isFocusTraversable = false
        }
    private val aztec = spinner("barcodes-aztec", BarcodeOptionsStore.AZTEC_RANGE, 1)
    private val pdf417 = spinner("barcodes-pdf417", BarcodeOptionsStore.PDF417_RANGE, 1)
    private val size = spinner("barcodes-size", BarcodeOptionsStore.SIZE_RANGE, 64)
    private val quiet = spinner("barcodes-quiet", BarcodeOptionsStore.QUIET_RANGE, 1)
    private val foregroundPicker = picker("barcodes-foreground")
    private val backgroundPicker = picker("barcodes-background")

    private val qrGroup = group("ECC", qrEcc)
    private val aztecGroup = group("ECC %", aztec)
    private val pdf417Group = group("Level", pdf417)

    /** The trailing group, empty here: the Template menu goes into it. */
    internal val trailingGroup = HBox(6.0).apply { alignment = Pos.CENTER_LEFT }

    val node =
        WrappingToolbar().apply {
            styleClass += "barcodes-options"
            hgap = 12.0
            leading.setAll(
                group("Format", format),
                qrGroup,
                aztecGroup,
                pdf417Group,
                group("Size", size),
                group("Quiet zone", quiet),
                group("Foreground", foregroundPicker),
                group("Background", backgroundPicker),
            )
            trailing.setAll(trailingGroup)
        }

    init {
        format.valueProperty().addListener { _, _, value ->
            if (!updating && value != null) model.setFormat(value)
        }
        qrEcc.valueProperty().addListener { _, _, value -> change { copy(qrEcc = value ?: qrEcc) } }
        aztec.valueProperty().addListener { _, _, value -> change { copy(aztecEccPercent = value) } }
        pdf417.valueProperty().addListener { _, _, value -> change { copy(pdf417Level = value) } }
        size.valueProperty().addListener { _, _, value -> change { copy(sizePx = value) } }
        quiet.valueProperty().addListener { _, _, value ->
            change { copy(quietZone = quietZoneToKeep(this, model.format, value)) }
        }
        foregroundPicker.valueProperty().addListener { _, _, value -> change { copy(foreground = value.toArgb()) } }
        backgroundPicker.valueProperty().addListener { _, _, value -> change { copy(background = value.toArgb()) } }
    }

    private fun change(edit: GenerateOptions.() -> GenerateOptions) {
        if (!updating) model.setOptions(model.options.edit())
    }

    /** Shows the format and options of the model without telling the model. */
    fun show() {
        updating = true
        try {
            val options = model.options
            if (format.value != model.format) format.value = model.format
            if (qrEcc.value != options.qrEcc) qrEcc.value = options.qrEcc
            aztec.valueFactory.value = options.aztecEccPercent
            pdf417.valueFactory.value = options.pdf417Level
            size.valueFactory.value = options.sizePx
            quiet.valueFactory.value = options.quietZoneFor(model.format)
            foregroundPicker.value = colorOf(options.foreground)
            backgroundPicker.value = colorOf(options.background)
        } finally {
            updating = false
        }
        show(qrGroup, model.format == CodeFormat.QR_CODE)
        show(aztecGroup, model.format == CodeFormat.AZTEC)
        show(pdf417Group, model.format == CodeFormat.PDF_417)
    }

    private fun show(
        node: Node,
        visible: Boolean,
    ) {
        node.isVisible = visible
        node.isManaged = visible
    }

    private fun spinner(
        id: String,
        range: IntRange,
        step: Int,
    ): Spinner<Int> {
        val factory = SpinnerValueFactory.IntegerSpinnerValueFactory(range.first, range.last, range.first, step)

        fun clamped(text: String?) = text?.trim()?.toIntOrNull()?.coerceIn(range)
        // The spinner commits the field's text itself, on the arrows and when it loses the focus: text that is no
        // number keeps the value, a number is clamped, so the model only ever sees a valid value.
        factory.converter =
            object : StringConverter<Int>() {
                override fun toString(value: Int?) = value?.toString().orEmpty()

                override fun fromString(text: String?) = clamped(text) ?: factory.value
            }
        return Spinner<Int>(factory).apply {
            this.id = id
            isEditable = true
            isFocusTraversable = false
            editor.isFocusTraversable = false
            prefWidth = 80.0
            // On Enter and when the field loses the focus, the text goes back to the value it was committed as.
            val settle = {
                clamped(editor.text)?.let { factory.value = it }
                editor.text = factory.value.toString()
            }
            editor.addEventHandler(ActionEvent.ACTION) { settle() }
            focusedProperty().addListener { _, _, focused -> if (!focused) settle() }
            editor.focusedProperty().addListener { _, _, focused -> if (!focused) settle() }
        }
    }

    private fun picker(id: String) =
        ColorPicker().apply {
            this.id = id
            isFocusTraversable = false
        }

    private fun group(
        label: String,
        control: Node,
    ) = HBox(6.0, Label(label), control).apply { alignment = Pos.CENTER_LEFT }

    private fun <T> titles(title: (T) -> String) =
        object : StringConverter<T>() {
            override fun toString(value: T?) = value?.let(title) ?: ""

            override fun fromString(string: String?): T? = null
        }
}
