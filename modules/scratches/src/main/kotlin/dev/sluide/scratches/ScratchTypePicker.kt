package dev.sluide.scratches

import dev.sluide.fileicons.fileIconFor
import dev.sluide.fileicons.show
import dev.sluide.scratches.picker.EntryGraphic
import dev.sluide.scratches.picker.FilterPicker
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.stage.Window
import org.kordamp.ikonli.javafx.FontIcon

/**
 * The popup of File > New Scratch File, as in IntelliJ: a filter field above the list of [types]. Typing filters by name
 * or extension (ignoring case); Enter picks the selected type, Up and Down move the selection while the focus stays in
 * the field, a click picks a row, Escape or a click elsewhere closes.
 */
class ScratchTypePicker(
    types: List<ScratchType> = SCRATCH_TYPES,
) {
    private val picker =
        FilterPicker(
            title = "New Scratch File",
            prompt = "Type",
            noMatch = "No matching type",
            items = types,
            filter = ::matchingTypes,
            entryGraphic = ::typeGraphic,
        )

    internal val field get() = picker.field

    internal val list get() = picker.list

    /** The selected type; `null` if none. */
    internal val selected: ScratchType? get() = picker.selected

    val isShowing: Boolean get() = picker.isShowing

    /**
     * Shows the popup centred horizontally near the top of [owner], the filter field focused and [preselected] (else
     * the first type) selected. A pick hides the popup and calls [onPick].
     */
    fun show(
        owner: Window,
        preselected: ScratchType?,
        onPick: (ScratchType) -> Unit,
    ) = picker.show(owner, preselected, onPick)

    fun hide() = picker.hide()
}

/** The type's icon, its name and the dimmed extension. */
private fun typeGraphic(): EntryGraphic<ScratchType> {
    val icon = FontIcon().apply { iconSize = ICON_SIZE }
    val name = Label()
    val extension = Label().apply { styleClass += "scratch-picker-extension" }
    return EntryGraphic(HBox(icon, name, extension).apply { styleClass += "scratch-picker-row" }) { type ->
        icon.show(fileIconFor("scratch.${type.extension}", directory = false, expanded = false) { true })
        name.text = type.name
        extension.text = ".${type.extension}"
    }
}

private const val ICON_SIZE = 16

/**
 * The [types] whose name or extension contains [text] ignoring case, after trimming it and dropping a leading dot;
 * all of them for empty [text].
 */
internal fun matchingTypes(
    types: List<ScratchType>,
    text: String?,
): List<ScratchType> {
    val wanted = text.orEmpty().trim().removePrefix(".")
    return types.filter { wanted.isEmpty() || it.name.contains(wanted, true) || it.extension.contains(wanted, true) }
}
