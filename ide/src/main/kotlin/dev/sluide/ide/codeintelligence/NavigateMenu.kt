package dev.sluide.ide.codeintelligence

import dev.sluide.codeintelligence.BACK_KEYS
import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FIND_USAGES_KEYS
import dev.sluide.codeintelligence.FORWARD_KEYS
import dev.sluide.codeintelligence.GO_TO_DEFINITION_KEYS
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem

/**
 * The Navigate menu of a window (ADR 0146), after View: Back, Forward | Go to Definition, Find Usages, acting on the
 * window's code intelligence. Back and Forward follow the history; the other two act on the focused editor with code
 * intelligence and are disabled, when the menu opens, without one or while its server cannot answer. Their keys are
 * the editors' own bindings, which consume the press, so the accelerators here run only where no such editor has the
 * focus, and a press acts once.
 */
internal fun navigateMenu(intelligence: CodeIntelligence): Menu {
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
    return Menu("Navigate", null, back, forward, SeparatorMenuItem(), definition, usages).apply {
        // The focus and the server's state are read when the menu opens.
        setOnShowing {
            definition.isDisable = intelligence.definitionBlocker() != null
            usages.isDisable = intelligence.usagesBlocker() != null
        }
    }
}
