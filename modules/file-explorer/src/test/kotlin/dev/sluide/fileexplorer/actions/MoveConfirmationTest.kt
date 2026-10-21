package dev.sluide.fileexplorer.actions

import kotlin.test.Test
import kotlin.test.assertEquals

class MoveConfirmationTest {
    @Test
    fun `names one entry and the folder`() {
        assertEquals(
            MoveQuestion("Move “a.txt” to “src”?", "", false),
            moveQuestion(listOf("a.txt"), emptyList(), "src"),
        )
    }

    @Test
    fun `counts and lists several entries`() {
        assertEquals(
            MoveQuestion("Move 3 entries to “Scratches”?", "“a.txt”\n“src”\n“b”", false),
            moveQuestion(listOf("a.txt", "src", "b"), emptyList(), "Scratches"),
        )
    }

    @Test
    fun `lists at most ten names, then counts the rest`() {
        val question = moveQuestion((1..12).map { "f$it" }, emptyList(), "src")

        assertEquals("Move 12 entries to “src”?", question.header)
        assertEquals((1..10).map { "“f$it”" } + "and 2 more", question.details.lines())
    }

    @Test
    fun `says which entries exist in the folder already and are skipped`() {
        assertEquals(
            MoveQuestion("Move “a.txt” to “src”?", "“b.txt” already exists in “src” and is skipped.", true),
            moveQuestion(listOf("a.txt"), listOf("b.txt"), "src"),
        )
        assertEquals(
            MoveQuestion(
                "Move 2 entries to “src”?",
                "“a.txt”\n“c”\n\nThese already exist in “src” and are skipped:\n“b.txt”\n“d”",
                true,
            ),
            moveQuestion(listOf("a.txt", "c"), listOf("b.txt", "d"), "src"),
        )
    }

    @Test
    fun `the button says that only the others are moved when some are skipped`() {
        assertEquals(MOVE_BUTTON, moveButton(moveQuestion(listOf("a"), emptyList(), "src")))
        assertEquals(MOVE_OTHERS_BUTTON, moveButton(moveQuestion(listOf("a"), listOf("b"), "src")))
        assertEquals("Move the others", MOVE_OTHERS_BUTTON.text)
    }
}
