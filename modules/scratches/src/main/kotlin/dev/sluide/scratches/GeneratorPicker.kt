package dev.sluide.scratches

import dev.sluide.scratches.picker.EntryGraphic
import dev.sluide.scratches.picker.FilterPicker
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.stage.Window

/**
 * The popup of File > New Generated Scratch File (ADR 0114): a filter field above the [generators] under the headers
 * of their groups, each with its name and the dimmed name of the file it creates. Typing filters by name, group title
 * or file name ([matchingGenerators]); a group's header shows while the group has matches. Keys and clicks as in
 * [ScratchTypePicker]; Up and Down skip the headers.
 */
class GeneratorPicker(
    generators: List<ScratchGenerator> = SCRATCH_GENERATORS,
) {
    private val picker =
        FilterPicker(
            title = "New Generated Scratch File",
            prompt = "Generator",
            noMatch = "No matching generator",
            items = generators,
            filter = ::matchingGenerators,
            headerOf = { it.group.title },
            popupWidth = WIDTH,
            entryGraphic = ::generatorGraphic,
        )

    internal val field get() = picker.field

    internal val list get() = picker.list

    /** The selected generator; `null` if none. */
    internal val selected: ScratchGenerator? get() = picker.selected

    val isShowing: Boolean get() = picker.isShowing

    /**
     * Shows the popup centred horizontally near the top of [owner], the filter field focused and [preselected] (else
     * the first generator) selected. A pick hides the popup and calls [onPick].
     */
    fun show(
        owner: Window,
        preselected: ScratchGenerator?,
        onPick: (ScratchGenerator) -> Unit,
    ) = picker.show(owner, preselected, onPick)

    fun hide() = picker.hide()

    private companion object {
        /** Wider than the type picker: names like "Password 20 (letters, digits)" plus their file names. */
        const val WIDTH = 440.0
    }
}

/** The generator's name and the dimmed name of its file. */
private fun generatorGraphic(): EntryGraphic<ScratchGenerator> {
    val name = Label()
    val fileName = Label().apply { styleClass += "scratch-picker-extension" }
    return EntryGraphic(HBox(name, fileName).apply { styleClass += "scratch-picker-row" }) { generator ->
        name.text = generator.name
        fileName.text = generator.fileName
    }
}
