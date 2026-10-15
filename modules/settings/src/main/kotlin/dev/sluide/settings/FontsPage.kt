package dev.sluide.settings

import javafx.beans.binding.Bindings
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextArea
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import org.springframework.stereotype.Component
import kotlin.math.abs

/**
 * Display → Fonts: family, size and style of the file explorer, the editor and the terminal, with a preview of the
 * edits. Nothing changes in the IDE until the dialog's OK or Apply.
 */
@Component
class FontsPage(
    settings: FontSettings,
) : SettingsPage {
    internal val model = FontsPageModel(settings)

    override val path = listOf("Display", "Fonts")

    override val node: Node by lazy(::createNode)

    override fun load() = model.load()

    override fun apply() = model.apply()

    /** The controls of one area, for tests. */
    internal class Row(
        val family: ComboBox<String>,
        val size: Spinner<Double>,
        val style: ComboBox<FontStyle>,
        val restoreDefaults: Button,
    )

    internal val rows = mutableMapOf<FontArea, Row>()

    /** The preview nodes, for tests: the explorer tree, the editor text and the terminal's regular text. */
    internal lateinit var explorerPreview: TreeView<String>
    internal lateinit var editorPreview: TextArea
    internal lateinit var terminalPreview: List<Text>

    private fun createNode(): Node {
        val allFamilies = Font.getFamilies().sorted()
        val monospaced = listOf("Monospaced") + allFamilies.filter(::isMonospaced)
        val grid =
            GridPane().apply {
                hgap = 8.0
                vgap = 6.0
                addRow(
                    0,
                    *listOf(
                        "",
                        "Font",
                        "Size",
                        "Style",
                    ).map { Label(it).apply { styleClass += "section-title" } }.toTypedArray(),
                )
            }
        FontArea.entries.forEachIndexed { index, area ->
            val families =
                if (area ==
                    FontArea.TERMINAL
                ) {
                    monospaced.distinct()
                } else {
                    (listOf("System", "Monospaced") + allFamilies).distinct()
                }
            val row = row(area, families)
            rows[area] = row
            // Labels and buttons keep their full text; the family box gives up width first.
            val label = Label(area.label).apply { minWidth = Region.USE_PREF_SIZE }
            row.restoreDefaults.minWidth = Region.USE_PREF_SIZE
            grid.addRow(index + 1, label, row.family, row.size, row.style, row.restoreDefaults)
        }
        val terminalNote =
            Label("The terminal lists monospaced fonts only, so its columns line up.").apply {
                styleClass += "preview-caption"
            }
        return VBox(
            8.0,
            grid,
            terminalNote,
            Label("Preview").apply { styleClass += "section-title" },
            Label("File Explorer").apply { styleClass += "preview-caption" },
            explorerPreview(),
            Label("Editor").apply { styleClass += "preview-caption" },
            editorPreview(),
            Label("Terminal").apply { styleClass += "preview-caption" },
            terminalPreview(),
        ).apply { styleClass += "fonts-page" }
    }

    private fun row(
        area: FontArea,
        families: List<String>,
    ): Row {
        val draft = model.draft(area)
        val family =
            ComboBox<String>().apply {
                items.setAll(families)
                isEditable = true
                prefWidth = 260.0
                minWidth = 120.0
                visibleRowCount = 15
            }
        val size =
            Spinner<Double>().apply {
                valueFactory =
                    SpinnerValueFactory.DoubleSpinnerValueFactory(
                        FontSettings.SIZES.start,
                        FontSettings.SIZES.endInclusive,
                        draft.get().size,
                        1.0,
                    )
                isEditable = true
                prefWidth = 90.0
            }
        val style = ComboBox<FontStyle>().apply { items.setAll(FontStyle.entries) }
        val restore = Button("Restore Defaults").apply { setOnAction { model.restoreDefaults(area) } }
        // Enter commits a typed value; without this it would also press the dialog's default button (OK).
        family.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.ENTER) {
                family.commitValue()
                event.consume()
            }
        }
        size.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.ENTER) {
                size.commitValue()
                event.consume()
            }
        }

        // Controls → draft. Unknown family names typed into the box are not taken.
        var updating = false

        fun edit(change: (FontSpec) -> FontSpec) {
            if (!updating) draft.set(change(draft.get()))
        }
        family.valueProperty().subscribe { value ->
            if (value != null &&
                value in families
            ) {
                edit { it.copy(family = value) }
            } else if (!updating) {
                family.value =
                    draft.get().family
            }
        }
        size.valueProperty().subscribe { value -> if (value != null) edit { it.copy(size = value) } }
        style.valueProperty().subscribe { value -> if (value != null) edit { it.copy(style = value) } }
        // Draft → controls, e.g. when the dialog opens again or defaults are restored.
        draft.subscribe { spec ->
            updating = true
            family.value = spec.family
            size.valueFactory.value = spec.size.coerceIn(FontSettings.SIZES)
            style.value = spec.style
            updating = false
        }
        return Row(family, size, style, restore)
    }

    private fun explorerPreview() =
        TreeView(
            TreeItem("my-project").apply {
                isExpanded = true
                children.addAll(
                    TreeItem("src").apply {
                        isExpanded = true
                        children.addAll(TreeItem("Main.kt"), TreeItem("Settings.kt"))
                    },
                    TreeItem("README.md"),
                    TreeItem("build.gradle.kts"),
                )
            },
        ).apply {
            prefHeight = 130.0
            isFocusTraversable = false
            styleProperty().bind(css(FontArea.EXPLORER))
            explorerPreview = this
        }

    private fun editorPreview() =
        TextArea(
            """
            fun main() {
                val greeting = "Hello, Thoreau!" // 0O 1lI
                println(greeting)
            }
            """.trimIndent(),
        ).apply {
            isEditable = false
            isFocusTraversable = false
            prefRowCount = 4
            styleProperty().bind(css(FontArea.EDITOR))
            editorPreview = this
        }

    /**
     * Looks like the terminal: dark background, light text, a coloured prompt and a bold line. The colours (and the
     * background in `settings.css`) copy the terminal's; keep them in sync with `TerminalColors` in the terminal
     * module, which `settings` cannot depend on.
     */
    private fun terminalPreview(): Node {
        val draft = model.draft(FontArea.TERMINAL)

        fun text(
            content: String,
            color: Color,
            bold: Boolean = false,
        ) = Text(content).apply {
            fill = color
            fontProperty().bind(
                Bindings.createObjectBinding({
                    draft.get().let { if (bold) it.copy(style = it.style.bolder()) else it }.toFont()
                }, draft),
            )
        }
        val regular =
            listOf(
                text(":~/my-project$ ls\n", Color.web("#d4d4d4")),
                text("README.md  build.gradle.kts  src\n", Color.web("#d4d4d4")),
            )
        terminalPreview = regular
        return TextFlow(
            text("user@host", Color.web("#00cd00")),
            regular[0],
            regular[1],
            text("BUILD SUCCESSFUL", Color.web("#cd0000"), bold = true),
        ).apply {
            styleClass += "terminal-preview"
            VBox.setVgrow(this, Priority.NEVER)
        }
    }

    private fun css(area: FontArea) =
        Bindings.createStringBinding({ model.draft(area).get().toCss() }, model.draft(area))

    private fun FontStyle.bolder() = if (italic) FontStyle.BOLD_ITALIC else FontStyle.BOLD

    internal companion object {
        /** Whether all characters of [family] are equally wide, measured with a narrow and a wide letter. */
        fun isMonospaced(family: String): Boolean {
            val font = Font.font(family, 20.0)
            if (font.family != family) return false // not installed, JavaFX fell back to another family
            val narrow = Text("iiiii").apply { this.font = font }.layoutBounds.width
            val wide = Text("MMMMM").apply { this.font = font }.layoutBounds.width
            return narrow > 0 && abs(narrow - wide) < 0.5
        }
    }
}
