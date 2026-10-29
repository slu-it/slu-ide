package dev.sluide.coloring.ui

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.ui.contrast.ContrastSummary
import dev.sluide.coloring.ui.contrast.contrastView
import javafx.css.PseudoClass
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.Border
import javafx.scene.layout.BorderStroke
import javafx.scene.layout.BorderStrokeStyle
import javafx.scene.layout.BorderWidths
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.util.Subscription
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignS

/**
 * The contrast of the current color against a second color, by WCAG 2 and APCA. The decisions are made by
 * [contrastView]; this pane shows them and writes the user's changes back to the window's state.
 */
internal class ContrastPane(
    private val state: ColoringState,
) {
    private val textRole = ToggleButton("Text")
    private val backgroundRole = ToggleButton("Background")
    private val secondField =
        TextField().apply {
            id = "contrast-second"
            styleClass += "coloring-input"
            prefColumnCount = 10
        }
    private var settingText = false
    private val secondSwatch = Swatch.of(state.second, state)

    private val sampleBox =
        VBox(6.0).apply {
            styleClass += "contrast-sample"
            padding = Insets(12.0)
        }
    private val normalSample = Label("The quick brown fox").apply { font = Font.font(null, FontWeight.NORMAL, 16.0) }
    private val largeSample = Label("The quick brown fox").apply { font = Font.font(null, FontWeight.BOLD, 24.0) }
    private val uiSample =
        Label("UI element").apply {
            styleClass += "contrast-ui-sample"
            padding = Insets(2.0, 10.0, 2.0, 10.0)
        }

    private val ratioLabel =
        Label().apply {
            id = "contrast-ratio"
            styleClass += "contrast-ratio"
        }
    private val badgeBox = FlowPane(6.0, 6.0)
    private val apcaLabel = Label().apply { styleClass += "contrast-apca" }
    private val apcaNote = Label("APCA (WCAG 3 draft), informational").apply { styleClass += "contrast-muted" }

    private val suggestionBox = HBox(8.0).apply { alignment = Pos.CENTER_LEFT }
    private val suggestionSwatches = mutableListOf<Swatch>()

    private val subscriptions = mutableListOf<Subscription>()

    val node: Node =
        VBox(
            10.0,
            Label("Contrast").apply { styleClass += "coloring-section-title" },
            roleRow(),
            secondRow(),
            sampleBox.apply { children += listOf(normalSample, largeSample, uiSample) },
            Label("WCAG 2").apply { styleClass += "coloring-section-title" },
            ratioLabel,
            badgeBox,
            suggestionBox,
            Label("APCA").apply { styleClass += "coloring-section-title" },
            apcaLabel,
            apcaNote,
        ).apply {
            styleClass += "contrast-pane"
            padding = Insets(12.0)
        }

    init {
        apcaLabel.tooltip =
            Tooltip(
                "APCA lightness contrast, rough guidance: Lc 75 or more for body text, 60 for content text, " +
                    "45 for large or bold text, 30 for non-text elements. The sign tells light text on dark " +
                    "(negative) from dark text on light (positive). APCA is a draft for WCAG 3.",
            ).apply {
                isWrapText = true
                maxWidth = 320.0
            }
        val group = ToggleGroup()
        textRole.toggleGroup = group
        backgroundRole.toggleGroup = group
        // A click on the selected toggle would clear the selection; the state decides, so the pane restores it.
        subscriptions +=
            group.selectedToggleProperty().subscribe { t -> if (t == null) showRole(state.currentIsText.get()) }
        textRole.setOnAction { state.currentIsText.set(true) }
        backgroundRole.setOnAction { state.currentIsText.set(false) }
        subscriptions += state.currentIsText.subscribe { isText -> showRole(isText) }

        secondField.textProperty().subscribe { text -> typed(text.orEmpty()) }
        subscriptions += state.second.subscribe { c -> syncField(c) }
        subscriptions += state.current.subscribe { _ -> refresh() }
        subscriptions += state.second.subscribe { _ -> refresh() }
        subscriptions += state.currentIsText.subscribe { _ -> refresh() }
    }

    private fun showRole(isText: Boolean) {
        textRole.isSelected = isText
        backgroundRole.isSelected = !isText
    }

    private fun typed(text: String) {
        if (settingText) return
        when (val result = ColorInputModel.onTyped(text)) {
            is InputResult.Valid -> {
                secondField.pseudoClassStateChanged(INVALID, false)
                state.second.set(result.color)
            }

            InputResult.Invalid -> {
                secondField.pseudoClassStateChanged(INVALID, true)
            }

            InputResult.Blank -> {
                secondField.pseudoClassStateChanged(INVALID, false)
            }
        }
    }

    private fun syncField(color: CssColor) {
        val text = ColorInputModel.textFor(color, secondField.text.orEmpty())
        secondField.pseudoClassStateChanged(INVALID, false)
        if (text == null) return
        settingText = true
        try {
            secondField.text = text
        } finally {
            settingText = false
        }
    }

    private fun roleRow() =
        HBox(
            8.0,
            Label("Current is:"),
            HBox(textRole, backgroundRole),
            Button("Swap", FontIcon(MaterialDesignS.SWAP_HORIZONTAL)).apply {
                id = "contrast-swap"
                setOnAction {
                    val first = state.current.get()
                    state.current.set(state.second.get())
                    state.second.set(first)
                }
            },
        ).apply { alignment = Pos.CENTER_LEFT }

    private fun secondRow() =
        HBox(
            8.0,
            Label("Second color:"),
            secondSwatch,
            secondField,
            Button("White").apply { setOnAction { state.second.set(CssColor.srgb(1.0, 1.0, 1.0)) } },
            Button("Black").apply { setOnAction { state.second.set(CssColor.srgb(0.0, 0.0, 0.0)) } },
        ).apply { alignment = Pos.CENTER_LEFT }

    private fun refresh() {
        val s = contrastView(state.current.get(), state.second.get(), state.currentIsText.get())
        val text = s.text.toFxColor()
        sampleBox.background = Background(BackgroundFill(s.sampleBackground.toFxColor(), null, null))
        normalSample.textFill = text
        largeSample.textFill = text
        uiSample.textFill = text
        uiSample.border = Border(BorderStroke(text, BorderStrokeStyle.SOLID, null, BorderWidths(1.0)))
        ratioLabel.text = s.ratioText
        badgeBox.children.setAll(
            s.badges.map { b ->
                Label("${b.label}: ${if (b.pass) "Pass" else "Fail"}").apply {
                    styleClass += listOf("contrast-badge", if (b.pass) "badge-pass" else "badge-fail")
                }
            },
        )
        apcaLabel.text = s.apcaText
        showSuggestion(s)
    }

    private fun showSuggestion(s: ContrastSummary) {
        suggestionSwatches.forEach { it.dispose() }
        suggestionSwatches.clear()
        suggestionBox.children.clear()
        val suggestion = s.suggestion ?: return
        suggestionBox.children += Label(suggestion.title)
        val color = suggestion.color
        if (color == null) {
            suggestionBox.children += Label(suggestion.noneText).apply { styleClass += "contrast-muted" }
            return
        }
        suggestionBox.children +=
            listOf(
                Swatch.of(color, state).also { suggestionSwatches += it },
                Label(suggestion.colorText),
                Button("Use").apply {
                    id = "contrast-use"
                    setOnAction {
                        if (state.currentIsText.get()) state.current.set(color) else state.second.set(color)
                    }
                },
            )
    }

    /** Releases the listeners on the window's state and the swatches. */
    fun dispose() {
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
        secondSwatch.dispose()
        suggestionSwatches.forEach { it.dispose() }
        suggestionSwatches.clear()
    }

    private companion object {
        val INVALID: PseudoClass = PseudoClass.getPseudoClass("invalid")
    }
}
