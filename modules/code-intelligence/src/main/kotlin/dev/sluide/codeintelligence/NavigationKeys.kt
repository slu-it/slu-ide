package dev.sluide.codeintelligence

import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination

// The keys of navigation (ADR 0146), for the editors' bindings and the Navigate menu.

/** Go to Definition at the caret: Shortcut+B, a key binding of bound editors. */
val GO_TO_DEFINITION_KEYS = KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN)

/** Find Usages at the caret: Alt+F7, a key binding of bound editors. */
val FIND_USAGES_KEYS = KeyCodeCombination(KeyCode.F7, KeyCombination.ALT_DOWN)

/** Back: Shortcut+Alt+Left, the Navigate menu's accelerator only. */
val BACK_KEYS = KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN)

/** Forward: Shortcut+Alt+Right, the Navigate menu's accelerator only. */
val FORWARD_KEYS = KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN)
