package dev.sluide.git

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitConflictCheckTest {
    @Test
    fun `a conflict marker starts a line, is as long as asked and is followed by a space or the line's end`() {
        fun marked(
            text: String,
            size: Int = 7,
        ) = hasConflictMarker(text.toByteArray(), size)

        assertTrue(marked("a\n<<<<<<< HEAD\nb\n"))
        assertTrue(marked(">>>>>>> 1a2b (x)"))
        assertTrue(marked("x\r\n<<<<<<<\r\n"))
        assertTrue(marked("<<<<<<<"))
        assertFalse(marked("=======\n"))
        assertFalse(marked(" <<<<<<< indented\n"))
        assertFalse(marked("<<<<<<<< eight\n"))
        assertFalse(marked("<<<<<<<x\n"))
        assertFalse(marked("<<<<<<< seven\n", size = 10))
        assertTrue(marked("<<<<<<<<<< ten\n", size = 10))
    }
}
