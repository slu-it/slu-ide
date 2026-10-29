package dev.sluide.coloring.ui

import dev.sluide.coloring.color.CssColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ColorInputModelTest {
    private val blue = CssColor.fromRgb8(59, 130, 246)

    @Test
    fun `a color in any notation is valid`() {
        assertEquals(InputResult.Valid(blue), ColorInputModel.onTyped("#3b82f6"))
        assertIs<InputResult.Valid>(ColorInputModel.onTyped("oklch(62% 0.19 260)"))
    }

    @Test
    fun `a bare hex and a trailing semicolon are accepted`() {
        assertEquals(InputResult.Valid(blue), ColorInputModel.onTyped("3b82f6"))
        assertEquals(InputResult.Valid(blue), ColorInputModel.onTyped("#3b82f6;"))
    }

    @Test
    fun `text that is no color is invalid`() {
        assertEquals(InputResult.Invalid, ColorInputModel.onTyped("#3b82f"))
        assertEquals(InputResult.Invalid, ColorInputModel.onTyped("rgb(1,2"))
    }

    @Test
    fun `blank text is neither valid nor invalid`() {
        assertEquals(InputResult.Blank, ColorInputModel.onTyped(""))
        assertEquals(InputResult.Blank, ColorInputModel.onTyped("   "))
    }

    @Test
    fun `the text is kept while it denotes the color`() {
        assertNull(ColorInputModel.textFor(blue, "rgb(59 130 246)"))
        assertNull(ColorInputModel.textFor(blue, "3b82f6"))
    }

    @Test
    fun `another color or unparsable text is replaced by the css text`() {
        assertEquals("#3b82f6", ColorInputModel.textFor(blue, "#ff0000"))
        assertEquals("#3b82f6", ColorInputModel.textFor(blue, "garbage"))
        assertEquals("#3b82f6", ColorInputModel.textFor(blue, ""))
    }
}
