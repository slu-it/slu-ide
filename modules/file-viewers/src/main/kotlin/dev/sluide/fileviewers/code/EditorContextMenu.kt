package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.EditorMenuTarget
import javafx.event.EventHandler
import javafx.event.EventTarget
import javafx.scene.Node
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.Clipboard
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.text.Font
import org.fxmisc.richtext.CodeArea

private val log = System.getLogger(EditorContextMenu::class.java.name)

/**
 * The context menu of a code view's [area] (ADR 0108): Undo, Redo | Cut, Copy, Paste, Select All, then after a
 * separator the items that [contributed] (the extensions' providers) give for the caret. Built anew each time it shows,
 * with the items that cannot act disabled ([menuStates]); the [area] is not editable while its view is read-only.
 *
 * RichTextFX shows it on a mouse request where the pointer is. A secondary press outside the selection first moves the
 * caret there; inside it keeps the selection. The Menu key and Shift+F10 ([MENU_KEYS]) show it at the caret, and so
 * does a request the platform makes from the keyboard (Windows); JavaFX on Linux makes none. The items show their keys
 * but install no accelerators (the area is no `Control`): the keys go through the area's own bindings. FX thread.
 */
internal class EditorContextMenu(
    private val area: CodeArea,
    private val contributed: () -> List<(EditorMenuTarget) -> List<MenuItem>>,
    private val clipboardHasText: () -> Boolean = { Clipboard.getSystemClipboard().hasString() },
) {
    private val undo = item("Undo", KeyCode.Z) { area.undo() }
    private val redo = item("Redo", KeyCode.Z, shift = true) { area.redo() }
    private val cut = item("Cut", KeyCode.X) { area.cut() }
    private val copy = item("Copy", KeyCode.C) { area.copy() }
    private val paste = item("Paste", KeyCode.V) { area.paste() }
    private val selectAll = item("Select All", KeyCode.A) { area.selectAll() }
    private val base = listOf(undo, redo, SeparatorMenuItem(), cut, copy, paste, selectAll)

    /** The menu, shown by RichTextFX through the area's `contextMenu`; never empty, which would keep it from showing. */
    private val menu =
        ContextMenu(*base.toTypedArray()).apply {
            styleClass += "code-editor-context-menu"
            // The popup inherits the editor font of the tab's content; a menu reads in the interface font.
            style =
                Font.getDefault().let {
                    "-fx-font-family: \"${it.family}\"; -fx-font-size: ${it.size}px; " +
                        "-fx-font-weight: normal; -fx-font-style: normal;"
                }
            setOnShowing { rebuild() }
        }

    private val pressFilter = EventHandler<MouseEvent>(::secondaryPressed)
    private val keyboardFilter = EventHandler<ContextMenuEvent>(::requestedByKeyboard)
    private val keyHandler = EventHandler<KeyEvent>(::keyPressed)

    init {
        area.contextMenu = menu
        area.addEventFilter(MouseEvent.MOUSE_PRESSED, pressFilter)
        area.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, keyboardFilter)
        area.addEventHandler(KeyEvent.KEY_PRESSED, keyHandler)
    }

    /** Sets the items and their states for the caret as it is now. */
    private fun rebuild() {
        val states =
            menuStates(
                canUndo = area.isUndoAvailable,
                canRedo = area.isRedoAvailable,
                hasSelection = area.selection.length > 0,
                editable = area.isEditable,
                hasText = area.length > 0,
                clipboardHasText = clipboardHasText(),
            )
        undo.isDisable = !states.undo
        redo.isDisable = !states.redo
        cut.isDisable = !states.cut
        copy.isDisable = !states.copy
        paste.isDisable = !states.paste
        selectAll.isDisable = !states.selectAll
        val target = EditorMenuTarget(area.currentParagraph, area.caretColumn, area.selectedText)
        val extra = contributed().flatMap { provider -> itemsOf(provider, target) }
        menu.items.setAll(base)
        if (extra.isNotEmpty()) menu.items += listOf(SeparatorMenuItem()) + extra
    }

    private fun itemsOf(
        provider: (EditorMenuTarget) -> List<MenuItem>,
        target: EditorMenuTarget,
    ): List<MenuItem> =
        try {
            provider(target)
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "Could not add items to the context menu of a code editor", e)
            emptyList()
        }

    /**
     * Moves the caret to where the secondary button (or the platform's popup trigger, e.g. Ctrl+click on macOS) is
     * pressed, unless that is on a selected character or in the gutter; before the menu shows, which on Linux and
     * macOS it does on the press. A popup trigger with the primary button is kept from RichTextFX, whose click would
     * drop the selection or start a drag.
     */
    private fun secondaryPressed(event: MouseEvent) {
        if (event.button != MouseButton.SECONDARY && !event.isPopupTrigger) return
        if (inGutter(event.target)) return
        if (event.button == MouseButton.PRIMARY) {
            area.requestFocus()
            event.consume()
        }
        val hit = area.hit(event.x, event.y)
        val selection = area.selection
        val character = hit.characterIndex
        if (character.isPresent && character.asInt >= selection.start && character.asInt < selection.end) return
        area.moveTo(hit.insertionIndex)
    }

    /** Whether [target] is in a row of the area's [Gutter]. */
    private fun inGutter(target: EventTarget?): Boolean =
        generateSequence(target as? Node) { it.parent }
            .takeWhile { it !== area }
            .any { Gutter.GUTTER_STYLE in it.styleClass }

    /** Shows the menu at the caret when the platform requests it by keyboard, rather than where the scene puts it. */
    private fun requestedByKeyboard(event: ContextMenuEvent) {
        if (!event.isKeyboardTrigger) return
        event.consume()
        showAtCaret(event.screenX, event.screenY)
    }

    private fun keyPressed(event: KeyEvent) {
        if (MENU_KEYS.none { it.match(event) }) return
        event.consume()
        val corner = area.localToScreen(0.0, 0.0) ?: return
        showAtCaret(corner.x, corner.y)
    }

    /** Shows the menu below the caret; at [screenX], [screenY] if the caret is not on screen. */
    private fun showAtCaret(
        screenX: Double,
        screenY: Double,
    ) {
        area.requestFocus()
        val caret = area.caretBounds.orElse(null)
        if (caret != null) {
            menu.show(area, caret.minX, caret.maxY)
        } else {
            menu.show(area, screenX, screenY)
        }
    }

    /** Hides the menu and stops reacting to the area. */
    fun dispose() {
        menu.hide()
        area.contextMenu = null
        area.removeEventFilter(MouseEvent.MOUSE_PRESSED, pressFilter)
        area.removeEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, keyboardFilter)
        area.removeEventHandler(KeyEvent.KEY_PRESSED, keyHandler)
    }
}

/** The keys that open the menu at the caret: the Menu key and Shift+F10, as on Windows and in GTK applications. */
internal val MENU_KEYS =
    listOf(KeyCodeCombination(KeyCode.CONTEXT_MENU), KeyCodeCombination(KeyCode.F10, KeyCombination.SHIFT_DOWN))

/** A menu item named [text] that does [action], showing Shortcut(+Shift)+[key], which the area binds itself. */
private fun item(
    text: String,
    key: KeyCode,
    shift: Boolean = false,
    action: () -> Unit,
) = MenuItem(text).apply {
    val modifiers = listOfNotNull(KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN.takeIf { shift })
    accelerator = KeyCodeCombination(key, *modifiers.toTypedArray())
    setOnAction { action() }
}

/** Which items of the [EditorContextMenu] can act. */
internal data class MenuStates(
    val undo: Boolean,
    val redo: Boolean,
    val cut: Boolean,
    val copy: Boolean,
    val paste: Boolean,
    val selectAll: Boolean,
)

/**
 * The [MenuStates] of a code editor's context menu. Undo, redo, cut and paste change the text, so they need it
 * [editable], as the area's keys do; copy needs a selection, cut too, paste text on the clipboard, select all text.
 */
internal fun menuStates(
    canUndo: Boolean,
    canRedo: Boolean,
    hasSelection: Boolean,
    editable: Boolean,
    hasText: Boolean,
    clipboardHasText: Boolean,
) = MenuStates(
    undo = editable && canUndo,
    redo = editable && canRedo,
    cut = editable && hasSelection,
    copy = hasSelection,
    paste = editable && clipboardHasText,
    selectAll = hasText,
)
