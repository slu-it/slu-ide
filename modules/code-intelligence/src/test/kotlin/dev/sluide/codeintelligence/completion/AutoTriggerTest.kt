package dev.sluide.codeintelligence.completion

import dev.sluide.fileviewers.TextChange
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

/** Which text changes open the completion by themselves: only single typed characters, at a word's end or a trigger. */
internal class AutoTriggerTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        // what, line before (the change at its end), inserted, typed, removed, next char, selection empty, expected
        "a typed dot, 'os', '.', '.', '', '', true, TRIGGER_CHARACTER",
        "a typed letter after a dot, 'os.', 'p', 'p', '', '', true, WORD",
        "the first letter of a word, 'x = ', 'p', 'p', '', '', true, WORD",
        "a letter extending a word, 'x = pa', 't', 't', '', ' ', true, WORD",
        "a letter inside a word, 'x = pa', 't', 't', '', 'h', true, NONE",
        "a digit starting a number, 'x = ', '1', '1', '', '', true, NONE",
        "a digit inside an identifier, 'x = v', '2', '2', '', '', true, WORD",
        "a space, 'x =', ' ', ' ', '', '', true, NONE",
        "undo or paste of one char (nothing typed), 'os', '.', , '', '', true, NONE",
        "a paste of several chars, 'os', '.pa', '.', '', '', true, NONE",
        "a typed char replacing a selection, 'os', '.', '.', 'x', '', true, NONE",
        "a typed char while text stays selected, 'os', '.', '.', '', '', false, NONE",
        "a typed key whose text differs (Enter), 'os', '\n', '\r', '', '', true, NONE",
        "a letter beyond the BMP, 'x = ', '\uD835\uDC65', '\uD835\uDC65', '', '', true, WORD",
        "a typed trigger character the change did not insert, 'os', ';', '.', '', '', true, NONE",
        "a typed key of several code points, 'x = ', 'ab', 'ab', '', '', true, NONE",
        "a combining mark extending a word, 'x = e', '\u0301', '\u0301', '', '', true, WORD",
    )
    fun `auto trigger`(
        what: String,
        before: String,
        inserted: String,
        typed: String?,
        removed: String,
        next: String,
        selectionEmpty: Boolean,
        expected: AutoTrigger,
    ) {
        val start = before.length
        val line = before + inserted
        val caret = start + inserted.length
        assertEquals(
            expected,
            autoTrigger(
                change = TextChange(start, removed, inserted),
                typed = typed,
                caret = caret,
                selectionEmpty = selectionEmpty,
                triggerCharacters = listOf("."),
                lineBeforeCaret = line,
                nextCodePoint = next.firstOrNull()?.code,
            ),
            what,
        )
    }

    @Test
    fun `a caret that is not right behind the typed character opens nothing`() {
        val change = TextChange(3, "", ".")

        for (caret in listOf(3, 5)) {
            assertEquals(
                AutoTrigger.NONE,
                autoTrigger(change, ".", caret, true, listOf("."), "os.", null),
                "caret $caret",
            )
        }
        assertEquals(AutoTrigger.TRIGGER_CHARACTER, autoTrigger(change, ".", 4, true, listOf("."), "os.", null))
    }

    @Test
    fun `a typed character that is no word character opens nothing, whatever length a word needs`() {
        // With words of any length allowed, only the typed character's own kind keeps "x = ;" from opening.
        val change = TextChange(5, "", ";")

        assertEquals(
            AutoTrigger.NONE,
            autoTrigger(change, ";", 6, true, listOf("."), "x = ;", null, minWordLength = 0),
        )
    }
}
