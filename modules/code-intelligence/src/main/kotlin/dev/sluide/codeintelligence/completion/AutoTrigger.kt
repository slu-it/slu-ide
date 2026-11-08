package dev.sluide.codeintelligence.completion

import dev.sluide.fileviewers.TextChange

/** Whether a text change opens the completion by itself, and how. */
internal enum class AutoTrigger {
    NONE,

    /** A server trigger character was typed (`.`): ask at once. */
    TRIGGER_CHARACTER,

    /** A word char was typed at the end of a word: ask after the debounce. */
    WORD,
}

/**
 * Whether [change] opens the completion by itself (ADR 0147): only a single character (one code point) the user typed
 * ([typed], from [dev.sluide.codeintelligence.binding.TypedKeys]; `null` for none, as after undo, redo, paste or an
 * applied edit), inserted without replacing a selection, with the caret right behind it. A server trigger character
 * asks at once; a word char asks when it ends a word ([lineBeforeCaret], the caret's line up to the caret;
 * [nextCodePoint] the code point after the caret) of at least [minWordLength] code points that does not start with a
 * digit (no completion while typing numbers).
 */
internal fun autoTrigger(
    change: TextChange,
    typed: String?,
    caret: Int,
    selectionEmpty: Boolean,
    triggerCharacters: Collection<String>,
    lineBeforeCaret: String,
    nextCodePoint: Int?,
    minWordLength: Int = 1,
): AutoTrigger {
    if (typed.isNullOrEmpty() || typed.codePointCount(0, typed.length) != 1) return AutoTrigger.NONE
    if (change.inserted != typed || change.removed.isNotEmpty()) return AutoTrigger.NONE
    if (!selectionEmpty || caret != change.start + typed.length) return AutoTrigger.NONE
    if (typed in triggerCharacters) return AutoTrigger.TRIGGER_CHARACTER
    if (!isWordCodePoint(typed.codePointAt(0))) return AutoTrigger.NONE
    val word = lineBeforeCaret.substring(wordStart(lineBeforeCaret, lineBeforeCaret.length))
    if (word.codePointCount(0, word.length) < minWordLength || Character.isDigit(word.codePointAt(0))) {
        return AutoTrigger.NONE
    }
    if (nextCodePoint != null && isWordCodePoint(nextCodePoint)) return AutoTrigger.NONE
    return AutoTrigger.WORD
}
