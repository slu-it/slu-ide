package dev.sluide.findinfiles

import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination

/**
 * Shortcut+Shift+F: Navigate → Find in Files… (ADR 0177). The explorer's folder menu shows the same combination
 * (its `FIND_IN_FOLDER_KEYS`); keep both equal.
 */
val FIND_IN_FILES_KEYS = KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
