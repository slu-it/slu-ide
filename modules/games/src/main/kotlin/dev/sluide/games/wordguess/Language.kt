package dev.sluide.games.wordguess

/**
 * A language of Word Guess: its [code] in `shared.json`, the [label] of the language menu and the letters a guess may
 * use. German has Ä, Ö and Ü as letters of their own; ß is written SS (ADR 0132).
 */
enum class Language(
    val code: String,
    val label: String,
    val alphabet: String,
) {
    EN("en", "English", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
    DE("de", "Deutsch", "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÜ"),
    ;

    companion object {
        /** The language with this [code], or `null` for an unknown or missing one. */
        fun ofCode(code: String?): Language? = entries.firstOrNull { it.code == code }
    }
}
