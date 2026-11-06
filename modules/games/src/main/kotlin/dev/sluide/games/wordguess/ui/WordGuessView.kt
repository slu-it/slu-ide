package dev.sluide.games.wordguess.ui

import dev.sluide.fileviewers.FocusWhenShown
import dev.sluide.fileviewers.WrappingToolbar
import dev.sluide.games.wordguess.KeyAction
import dev.sluide.games.wordguess.Language
import dev.sluide.games.wordguess.MIN_TILE
import dev.sluide.games.wordguess.SECTION_GAP_FACTOR
import dev.sluide.games.wordguess.SubmitResult
import dev.sluide.games.wordguess.WordGuessModel
import dev.sluide.games.wordguess.keyAction
import dev.sluide.games.wordguess.tileSize
import dev.sluide.games.wordguess.typedLetter
import javafx.geometry.Bounds
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.ScrollPane
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon

/**
 * The Word Guess tab: toolbar, grid, on-screen keyboard and status line. It only shows [model] and forwards input;
 * the view that shows a model owns its [WordGuessModel.onChange] until [dispose].
 */
internal class WordGuessView(
    private val model: WordGuessModel,
) {
    val grid = GridView()
    val keyboard =
        KeyboardView(
            onLetter = { type(it) },
            onEnter = ::submit,
            onBackspace = { model.backspace().also { grid.requestFocus() } },
        )
    val languageMenu = MenuButton()
    val nextWordButton = Button()
    private val languageGroup = ToggleGroup()
    private val languageItems =
        Language.entries.associateWith { language ->
            RadioMenuItem(language.label).apply {
                toggleGroup = languageGroup
                setOnAction {
                    model.chooseLanguage(language)
                    grid.requestFocus()
                }
            }
        }
    val status = Label().apply { styleClass += "wg-status" }
    private val content =
        VBox(grid, keyboard).apply {
            alignment = Pos.CENTER
            isFillWidth = false
            styleClass += "wg-content"
        }

    val node: Node =
        BorderPane().apply {
            styleClass += "wordguess"
            stylesheets +=
                checkNotNull(WordGuessView::class.java.getResource("/dev/sluide/games/wordguess/wordguess.css"))
                    .toExternalForm()
            top = toolbar()
            center =
                ScrollPane(content).apply {
                    styleClass += "wg-scroll"
                    isFitToWidth = true
                    isFitToHeight = true
                    isFocusTraversable = false
                    viewportBoundsProperty().addListener { _, _, bounds -> resize(bounds) }
                }
            bottom = HBox(status).apply { styleClass += "wg-status-bar" }
        }

    /** The grid has no scene yet when the content is focused before its first layout (it is in a scroll pane). */
    private val focusGrid = FocusWhenShown()

    private val installed: () -> Unit = ::refresh

    init {
        grid.setOnKeyPressed(::keyPressed)
        grid.setOnKeyTyped(::keyTyped)
        model.onChange = installed
        // The editor area focuses the tab's content; the keys belong to the grid.
        node.focusedProperty().addListener { _, _, focused -> if (focused) focusGrid.request(grid) }
        // Consumed, or the scroll pane around the grid takes the focus afterwards.
        grid.setOnMousePressed {
            grid.requestFocus()
            it.consume()
        }
        resize(null)
        refresh()
    }

    private fun toolbar(): Node {
        languageMenu.apply {
            isFocusTraversable = false
            items.setAll(languageItems.values)
        }
        nextWordButton.apply {
            graphic = FontIcon("mdi2s-skip-next").apply { iconSize = ICON_SIZE }
            isFocusTraversable = false
            tooltip = Tooltip("Next Word (F2)")
            setOnAction {
                model.nextWord()
                grid.requestFocus()
            }
        }
        return WrappingToolbar().apply {
            hgap = 6.0
            leading.setAll(languageMenu, nextWordButton)
            styleClass += "wg-toolbar"
        }
    }

    private fun type(letter: Char) {
        model.type(letter)
        grid.requestFocus()
    }

    private fun submit() {
        val result = model.submit()
        if (result == SubmitResult.NOT_ENOUGH_LETTERS || result == SubmitResult.NOT_IN_LIST) {
            grid.shake(model.activeRow)
        }
        grid.requestFocus()
    }

    private fun keyPressed(event: KeyEvent) {
        val action =
            keyAction(event.code, event.isShortcutDown || event.isAltDown || event.isControlDown || event.isMetaDown)
                ?: return
        when (action) {
            KeyAction.SUBMIT -> submit()
            KeyAction.BACKSPACE -> model.backspace()
            KeyAction.NEXT_WORD -> model.nextWord()
        }
        event.consume()
    }

    private fun keyTyped(event: KeyEvent) {
        val letter =
            typedLetter(
                event.character,
                model.language,
                shortcut = event.isShortcutDown,
                control = event.isControlDown,
                meta = event.isMetaDown,
                alt = event.isAltDown,
            ) ?: return
        model.type(letter)
        event.consume()
    }

    private fun resize(bounds: Bounds?) {
        val side = bounds?.let { tileSize(it.width - 2 * PADDING, it.height - 2 * PADDING) } ?: MIN_TILE
        content.spacing = side * SECTION_GAP_FACTOR
        content.setPadding(Insets(PADDING))
        grid.applyTile(side)
        keyboard.applyTile(side)
        grid.update(model.rows)
    }

    private fun refresh() {
        grid.update(model.rows)
        keyboard.show(model.language)
        keyboard.update(model.letterStates)
        status.text = model.statusText
        languageMenu.text = model.language.label
        languageItems[model.language]?.isSelected = true
    }

    /** The model stops notifying this view (unless a newer view has taken it over). */
    fun dispose() {
        focusGrid.cancel()
        if (model.onChange === installed) model.onChange = {}
    }

    private companion object {
        const val ICON_SIZE = 16
        const val PADDING = 8.0
    }
}
