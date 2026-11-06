@file:Suppress("ktlint:standard:filename")

package dev.sluide.games.wordguess

/** A key of the on-screen keyboard. */
internal sealed interface KeyboardKey {
    data class Letter(
        val letter: Char,
    ) : KeyboardKey

    data object Enter : KeyboardKey

    data object Backspace : KeyboardKey
}

/** The rows of the on-screen keyboard of [language]: QWERTY for English, QWERTZ with the umlauts for German. */
internal fun keyboardRows(language: Language): List<List<KeyboardKey>> {
    val letters =
        when (language) {
            Language.EN -> listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
            Language.DE -> listOf("QWERTZUIOPÜ", "ASDFGHJKLÖÄ", "YXCVBNM")
        }.map { row -> row.map<KeyboardKey> { KeyboardKey.Letter(it) } }
    return listOf(letters[0], letters[1], listOf(KeyboardKey.Enter) + letters[2] + KeyboardKey.Backspace)
}
