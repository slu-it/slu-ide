package dev.sluide.todos.view

import dev.sluide.todos.TodoScope
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.HBox

/** The Shared|Project segmented toggle of the dialogs; a choice is always selected, Project can be disabled. */
internal class ScopeSelector(
    initial: TodoScope,
    projectPossible: Boolean,
) {
    private val group = ToggleGroup()
    val buttons: Map<TodoScope, ToggleButton> =
        TodoScope.entries.associateWith { scope ->
            ToggleButton(if (scope == TodoScope.SHARED) "Shared" else "Project").apply {
                toggleGroup = group
                userData = scope
                isDisable = scope == TodoScope.PROJECT && !projectPossible
                styleClass += "todo-segment"
            }
        }

    val row =
        HBox(buttons.getValue(TodoScope.SHARED), buttons.getValue(TodoScope.PROJECT)).apply {
            styleClass += "todo-segmented"
        }

    /** The scope that is selected. */
    val scope: TodoScope get() = group.selectedToggle.userData as TodoScope

    init {
        group.selectToggle(buttons.getValue(initial))
        // Clicking the selected button keeps it.
        group.selectedToggleProperty().subscribe { old, new -> if (new == null) group.selectToggle(old) }
    }

    /** Calls [listener] with the scope whenever the selection changes (not at once). */
    fun onChange(listener: (TodoScope) -> Unit) {
        group.selectedToggleProperty().addListener { _, _, new -> if (new != null) listener(new.userData as TodoScope) }
    }
}
