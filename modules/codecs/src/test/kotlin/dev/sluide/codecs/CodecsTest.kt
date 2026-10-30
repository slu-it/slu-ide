package dev.sluide.codecs

import dev.sluide.codecs.conversion.Side
import dev.sluide.codecs.conversion.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals

class CodecsTest {
    @Test
    fun `open puts the text into the model and shows the tab`() {
        val codecs = Codecs()
        var shown = 0
        codecs.showTab = { shown++ }

        codecs.open("just some words")

        assertEquals(TextContent.Text("just some words"), codecs.model.text)
        assertEquals(Side.TEXT, codecs.model.source)
        assertEquals(1, shown)
    }
}
