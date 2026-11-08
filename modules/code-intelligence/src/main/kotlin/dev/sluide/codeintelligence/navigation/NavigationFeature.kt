package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.FIND_USAGES_KEYS
import dev.sluide.codeintelligence.GO_TO_DEFINITION_KEYS
import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.languageservers.RequestSlot
import dev.sluide.textediting.TextPosition
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyCombination

/**
 * Go to definition and find usages in a bound editor (ADR 0146): Shortcut+B and Alt+F7 at the caret, Shortcut+hover
 * and Shortcut+click ([DefinitionLink]), the context menu items, and the actions the Navigate menu calls through
 * [Navigation]. Both keys are the editor's own bindings and always taken in a bound editor (a note says why when they
 * cannot act), so the Navigate menu's accelerators, which JavaFX runs only for presses nobody consumed, never run a
 * second time. One list of places per editor ([TargetChooser]) and one request at a time.
 */
internal class NavigationFeature(
    private val navigation: Navigation,
    private val lines: LineReader = DiskLines(),
    private val view: (ExtensibleCodeEditor) -> TargetView = ::TargetPopup,
) : BindingFeature {
    override fun attach(binding: DocumentBinding): AutoCloseable {
        val editor = binding.editor
        val requests = RequestSlot()
        val chooser = TargetChooser(binding, navigation, view(editor), lines).apply { onHidden = requests::cancel }
        val definitions = Definitions(binding, chooser, navigation, requests)
        val usages = Usages(binding, chooser, navigation, requests)
        val actions =
            object : BindingNavigation {
                override fun goToDefinition(offset: Int) = definitions.go(offset)

                override fun findUsages(offset: Int) = usages.find(offset)
            }
        val handles =
            listOf(
                chooser,
                AutoCloseable { requests.cancel() },
                navigation.register(binding, actions),
                editor.addKeyBinding(GO_TO_DEFINITION_KEYS) {
                    definitions.go(editor.caretOffset)
                    true
                },
                editor.addKeyBinding(FIND_USAGES_KEYS) {
                    usages.find(editor.caretOffset)
                    true
                },
                // The places listed, and an answer on its way, are of the text before the edit.
                editor.onTextChange {
                    requests.cancel()
                    chooser.hide()
                },
                DefinitionLink(binding, definitions::go),
                binding.addContextMenuItems { target ->
                    val offset = editor.offsetOf(TextPosition(target.line, target.column))
                    listOf(
                        menuItem("Go to Definition", GO_TO_DEFINITION_KEYS, navigationBlocker(binding, DEFINITION)) {
                            definitions.go(offset)
                        },
                        menuItem("Find Usages", FIND_USAGES_KEYS, navigationBlocker(binding, REFERENCES)) {
                            usages.find(offset)
                        },
                    )
                },
            )
        return AutoCloseable { handles.asReversed().forEach { it.close() } }
    }
}

/** A context menu item showing [keys] (bound by the editor, not installed here), disabled with [blocker] if any. */
private fun menuItem(
    label: String,
    keys: KeyCombination,
    blocker: String?,
    action: () -> Unit,
) = MenuItem(if (blocker == null) label else "$label ($blocker)").apply {
    accelerator = keys
    isDisable = blocker != null
    setOnAction { action() }
}
