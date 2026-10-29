package dev.sluide.fileviewers.code

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

/** Which items of a code editor's context menu can act ([menuStates]); the menu itself is `EditorContextMenuUiTest`'s. */
class EditorContextMenuTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        // case, canUndo, canRedo, hasSelection, editable, hasText, clipboard -> undo, redo, cut, copy, paste, selectAll
        "empty text, false, false, false, true, false, false, false, false, false, false, false, false",
        "history, true, true, false, true, true, false, true, true, false, false, false, true",
        "selection, false, false, true, true, true, false, false, false, true, true, false, true",
        "clipboard text, false, false, false, true, true, true, false, false, false, false, true, true",
        "read-only, true, true, true, false, true, true, false, false, false, true, false, true",
    )
    fun `items act only when they can`(
        case: String,
        canUndo: Boolean,
        canRedo: Boolean,
        hasSelection: Boolean,
        editable: Boolean,
        hasText: Boolean,
        clipboard: Boolean,
        undo: Boolean,
        redo: Boolean,
        cut: Boolean,
        copy: Boolean,
        paste: Boolean,
        selectAll: Boolean,
    ) {
        assertEquals(
            MenuStates(undo, redo, cut, copy, paste, selectAll),
            menuStates(canUndo, canRedo, hasSelection, editable, hasText, clipboard),
            case,
        )
    }
}
