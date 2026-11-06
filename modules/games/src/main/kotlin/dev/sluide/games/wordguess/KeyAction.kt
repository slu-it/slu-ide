package dev.sluide.games.wordguess

import javafx.scene.input.KeyCode

/** What a pressed key does besides typing letters. */
internal enum class KeyAction { SUBMIT, BACKSPACE, NEXT_WORD }

/**
 * The action of [code], or null for other keys and while a modifier ([hasModifier]: Shortcut, Alt or Ctrl) is down, so
 * that the menu accelerators keep working.
 */
internal fun keyAction(
    code: KeyCode,
    hasModifier: Boolean = false,
): KeyAction? {
    if (hasModifier) return null
    return when (code) {
        KeyCode.ENTER -> KeyAction.SUBMIT
        KeyCode.BACK_SPACE -> KeyAction.BACKSPACE
        KeyCode.F2 -> KeyAction.NEXT_WORD
        else -> null
    }
}

/**
 * The upper-case letter a KEY_TYPED [character] types in [language], or null for anything else (other characters,
 * ß, several characters, or while Shortcut, Ctrl or Meta is down). The typed character, not the key code, makes dead
 * keys and Option combinations produce Ä, Ö and Ü.
 *
 * [alt] is let through: the app has no Alt+letter binding (no mnemonics) and some layouts type letters with
 * Alt/Option. Windows AltGr arrives as Ctrl+Alt and stays blocked (a known limit).
 */
internal fun typedLetter(
    character: String,
    language: Language,
    shortcut: Boolean = false,
    control: Boolean = false,
    meta: Boolean = false,
    @Suppress("UNUSED_PARAMETER") alt: Boolean = false,
): Char? {
    if (shortcut || control || meta || character.length != 1) return null
    val letter = character[0].uppercaseChar()
    return letter.takeIf { it in language.alphabet }
}
