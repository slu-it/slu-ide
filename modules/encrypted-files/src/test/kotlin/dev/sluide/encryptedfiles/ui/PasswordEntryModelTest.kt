package dev.sluide.encryptedfiles.ui

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordEntryModelTest {
    private val model = PasswordEntryModel()

    @Test
    fun `eleven code points are too short and twelve are enough, an emoji counting once`() {
        val eleven = "a".repeat(10) + "😀"
        model.update(eleven, eleven)
        assertEquals(PasswordEntryModel.TOO_SHORT, model.problem)
        assertFalse(model.valid)

        val twelve = "a".repeat(11) + "😀"
        model.update(twelve, twelve)
        assertNull(model.problem)
        assertTrue(model.valid)
    }

    @Test
    fun `a repeat that differs says so, also an empty one`() {
        model.update("correct horse battery", "correct horse batterY")
        assertEquals(PasswordEntryModel.DIFFER, model.problem)
        model.update("correct horse battery", "")
        assertEquals(PasswordEntryModel.DIFFER, model.problem)
    }

    @ParameterizedTest
    @CsvSource("'',''", "short,Weak", "abcdefghijkl,Weak", "Abcdefgh1jkl,OK", "correct horse battery staple,Strong")
    fun `the hint follows the strength of the new password`(
        typed: String,
        hint: String,
    ) {
        model.update(typed, typed)
        assertEquals(hint, model.hint)
    }

    @Test
    fun `Change Password also needs a current password, which the new one must not repeat`() {
        val change = PasswordEntryModel(requireCurrent = true)
        change.update("correct horse battery", "correct horse battery")
        assertEquals(PasswordEntryModel.ENTER_CURRENT, change.problem)
        change.update(
            "correct horse battery",
            "correct horse battery",
            current = StringBuilder("correct horse battery"),
        )
        assertEquals(PasswordEntryModel.SAME_AS_CURRENT, change.problem)
        assertFalse(change.valid)
        change.update("correct horse battery", "correct horse battery", current = "old")
        assertTrue(change.valid)
    }
}
