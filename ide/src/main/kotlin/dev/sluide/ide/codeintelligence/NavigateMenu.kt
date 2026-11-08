package dev.sluide.ide.codeintelligence

import dev.sluide.codeintelligence.BACK_KEYS
import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FILE_STRUCTURE_KEYS
import dev.sluide.codeintelligence.FIND_USAGES_KEYS
import dev.sluide.codeintelligence.FORWARD_KEYS
import dev.sluide.codeintelligence.GO_TO_DEFINITION_KEYS
import dev.sluide.codeintelligence.GO_TO_SYMBOL_KEYS
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.stage.Window

/**
 * The Navigate menu of [window] (ADR 0146, ADR 0149), after View: Back, Forward | Go to Definition, Find Usages, File
 * Structure, Go to Symbol…, acting on the window's code intelligence. Back and Forward follow the history; the next
 * three act on the focused editor with code intelligence and are disabled, when the menu opens, without one or while
 * its server cannot answer. Their keys are the editors' own bindings, which consume the press, so the accelerators here
 * run only where no such editor has the focus, and a press acts once. Go to Symbol acts window-wide; it is disabled
 * only while the menu shows without a server to ask, so its accelerator always acts and says why when it cannot.
 */
internal fun navigateMenu(
    intelligence: CodeIntelligence,
    window: Window,
): Menu {
    val back =
        MenuItem("Back").apply {
            accelerator = BACK_KEYS
            disableProperty().bind(intelligence.history.canGoBack.not())
            setOnAction { intelligence.back() }
        }
    val forward =
        MenuItem("Forward").apply {
            accelerator = FORWARD_KEYS
            disableProperty().bind(intelligence.history.canGoForward.not())
            setOnAction { intelligence.forward() }
        }
    val definition =
        MenuItem("Go to Definition").apply {
            accelerator = GO_TO_DEFINITION_KEYS
            setOnAction { intelligence.goToDefinition() }
        }
    val usages =
        MenuItem("Find Usages").apply {
            accelerator = FIND_USAGES_KEYS
            setOnAction { intelligence.findUsages() }
        }
    val structure =
        MenuItem("File Structure").apply {
            accelerator = FILE_STRUCTURE_KEYS
            setOnAction { intelligence.fileStructure() }
        }
    val symbol =
        MenuItem("Go to Symbol…").apply {
            accelerator = GO_TO_SYMBOL_KEYS
            setOnAction { intelligence.goToSymbol(window) }
        }
    return Menu("Navigate", null, back, forward, SeparatorMenuItem(), definition, usages, structure, symbol).apply {
        // The focus and the servers' states are read when the menu opens.
        setOnShowing {
            definition.isDisable = intelligence.definitionBlocker() != null
            usages.isDisable = intelligence.usagesBlocker() != null
            structure.isDisable = intelligence.fileStructureBlocker() != null
            symbol.isDisable = intelligence.goToSymbolBlocker() != null
        }
        setOnHidden { symbol.isDisable = false }
    }
}
