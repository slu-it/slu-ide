package dev.sluide.codecs.ui

import dev.sluide.codecs.codec.Ascii85Variant
import dev.sluide.codecs.codec.CHARSETS
import dev.sluide.codecs.codec.CodecId
import dev.sluide.codecs.codec.Compression
import dev.sluide.codecs.codec.HashOutput
import dev.sluide.codecs.codec.HexSeparator
import dev.sluide.codecs.codec.UnicodeForm
import dev.sluide.codecs.codec.UrlMode
import dev.sluide.codecs.conversion.CodecSettings
import dev.sluide.codecs.conversion.ConversionModel
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.CheckBox
import javafx.scene.control.ChoiceBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.util.StringConverter
import java.nio.charset.Charset

/** The options of the current codec; every control writes into the model's settings and follows them. */
internal class OptionsBar(
    private val model: ConversionModel,
) {
    private val syncs = ArrayList<(CodecSettings) -> Unit>()
    private var shownCodec: CodecId? = null
    private var updating = false

    val node: FlowPane =
        FlowPane(16.0, 6.0).apply {
            styleClass += "codecs-options"
            padding = Insets(8.0)
            alignment = Pos.CENTER_LEFT
        }

    init {
        refresh()
    }

    fun refresh() {
        val settings = model.settings
        updating = true
        try {
            if (settings.codec != shownCodec) {
                shownCodec = settings.codec
                build(settings)
            }
            syncs.forEach { it(settings) }
        } finally {
            updating = false
        }
    }

    private fun build(settings: CodecSettings) {
        syncs.clear()
        node.children.clear()
        when (settings.codec) {
            CodecId.BASE64 -> {
                check("URL-safe", { it.base64.urlSafe }) { s, v -> s.copy(base64 = s.base64.copy(urlSafe = v)) }
                check("Padding", { it.base64.padding }) { s, v -> s.copy(base64 = s.base64.copy(padding = v)) }
                check("Wrap at 76", { it.base64.wrap76 }) { s, v -> s.copy(base64 = s.base64.copy(wrap76 = v)) }
                check("data: URI", { it.base64.dataUri }) { s, v -> s.copy(base64 = s.base64.copy(dataUri = v)) }
            }

            CodecId.BASE32 -> {
                check(
                    "base32hex",
                    { it.base32.hexAlphabet },
                ) { s, v -> s.copy(base32 = s.base32.copy(hexAlphabet = v)) }
                check("Padding", { it.base32.padding }) { s, v -> s.copy(base32 = s.base32.copy(padding = v)) }
            }

            CodecId.ASCII85 -> {
                choice("Variant", Ascii85Variant.entries, Ascii85Variant::title, { it.ascii85 }) { s, v ->
                    s.copy(ascii85 = v)
                }
            }

            CodecId.HEX -> {
                check("Uppercase", { it.hex.upperCase }) { s, v -> s.copy(hex = s.hex.copy(upperCase = v)) }
                choice("Separator", HexSeparator.entries, HexSeparator::title, { it.hex.separator }) { s, v ->
                    s.copy(hex = s.hex.copy(separator = v))
                }
            }

            CodecId.URL -> {
                choice("Mode", UrlMode.entries, UrlMode::title, { it.url }) { s, v -> s.copy(url = v) }
            }

            CodecId.HTML -> {
                check("Non-ASCII as references", { it.htmlNonAscii }) { s, v -> s.copy(htmlNonAscii = v) }
            }

            CodecId.JSON -> {
                check("Non-ASCII as \\u escapes", { it.jsonNonAscii }) { s, v -> s.copy(jsonNonAscii = v) }
            }

            CodecId.JAVA -> {
                check(
                    "Non-ASCII as \\u escapes",
                    { it.java.nonAscii },
                ) { s, v -> s.copy(java = s.java.copy(nonAscii = v)) }
                check("Kotlin (escape $)", { it.java.kotlinDollar }) { s, v ->
                    s.copy(java = s.java.copy(kotlinDollar = v))
                }
            }

            CodecId.UNICODE -> {
                choice("Form", UnicodeForm.entries, UnicodeForm::title, { it.unicode.form }) { s, v ->
                    s.copy(unicode = s.unicode.copy(form = v))
                }
                check("All characters", { it.unicode.allCharacters }) { s, v ->
                    s.copy(unicode = s.unicode.copy(allCharacters = v))
                }
            }

            CodecId.HASH -> {
                keyField()
                choice("Output", HashOutput.entries, HashOutput::title, { it.hash.output }) { s, v ->
                    s.copy(hash = s.hash.copy(output = v))
                }
            }

            CodecId.BASE58, CodecId.JWT -> {
                Unit
            }
        }
        if (settings.compressionApplies) {
            choice("Compression", Compression.entries, Compression::title, { it.compression }) { s, v ->
                s.copy(compression = v)
            }
        }
        if (settings.lineByLineApplies) check("Line by line", { it.lineByLine }) { s, v -> s.copy(lineByLine = v) }
        if (settings.charsetApplies) charsetBox()
    }

    private fun check(
        label: String,
        get: (CodecSettings) -> Boolean,
        set: (CodecSettings, Boolean) -> CodecSettings,
    ) {
        val box = CheckBox(label)
        box.selectedProperty().subscribe { selected ->
            if (!updating) model.updateSettings { set(it, selected) }
        }
        syncs += { box.isSelected = get(it) }
        node.children += box
    }

    private fun <T> choice(
        label: String,
        items: List<T>,
        title: (T) -> String,
        get: (CodecSettings) -> T,
        set: (CodecSettings, T) -> CodecSettings,
    ) {
        val box =
            ChoiceBox<T>().apply {
                this.items.setAll(items)
                converter = titles(title)
            }
        box.valueProperty().subscribe { value ->
            if (!updating && value != null) model.updateSettings { set(it, value) }
        }
        syncs += { box.value = get(it) }
        labeled(label, box)
    }

    private fun charsetBox() {
        val box =
            ComboBox<Charset>().apply {
                items.setAll(CHARSETS)
                converter = titles { it.displayName() }
            }
        box.valueProperty().subscribe { value ->
            if (!updating && value != null) model.updateSettings { it.copy(charset = value) }
        }
        syncs += { box.value = it.charset }
        labeled("Charset", box)
    }

    private fun keyField() {
        val field =
            TextField().apply {
                id = "codecs-key"
                promptText = "HMAC key (optional)"
                prefColumnCount = 18
            }
        field.textProperty().subscribe { key ->
            if (!updating) model.updateSettings { it.copy(hash = it.hash.copy(key = key)) }
        }
        syncs += { if (field.text != it.hash.key) field.text = it.hash.key }
        labeled("Key", field)
    }

    private fun labeled(
        label: String,
        control: Node,
    ) {
        node.children += HBox(6.0, Label(label), control).apply { alignment = Pos.CENTER_LEFT }
    }

    private fun <T> titles(title: (T) -> String) =
        object : StringConverter<T>() {
            override fun toString(value: T?) = value?.let(title) ?: ""

            override fun fromString(string: String?): T? = null
        }
}
