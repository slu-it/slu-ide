package dev.sluide.codeintelligence.completion

import org.eclipse.lsp4j.CompletionItemKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The secondary text of a completion row: the detail on one line and shortened, else the kind's name. */
class CompletionKindsTest {
    @Test
    fun `the detail on one line, shortened, else the kind's name, ty's Struct an attribute`() {
        assertEquals("<module 'os.path'>", secondaryText("<module 'os.path'>", CompletionItemKind.Module))
        val signature = "def pathconf(path: int | str | bytes | PathLike[str], name: str | int) -> int"
        assertEquals("def pathconf(path: int | str | bytes | …", secondaryText(signature, CompletionItemKind.Function))
        assertEquals(DETAIL_LENGTH, secondaryText(signature, null)?.length)
        assertEquals("def f( a, b)", secondaryText("def f(\n    a,\n b)", null))
        assertEquals("attribute", secondaryText(" ", CompletionItemKind.Struct))
        assertNull(secondaryText(null, null), "an item without a kind")
    }

    @Test
    fun `a detail of exactly the shown length stays whole, one longer is cut with an ellipsis`() {
        val exact = "a".repeat(DETAIL_LENGTH)

        assertEquals(exact, secondaryText(exact, null))
        assertEquals("a".repeat(DETAIL_LENGTH - 1) + "…", secondaryText(exact + "a", null))
    }

    @Test
    fun `no or an empty detail shows the kind's name`() {
        assertEquals("method", secondaryText(null, CompletionItemKind.Method))
        assertEquals("method", secondaryText("", CompletionItemKind.Method))
        assertEquals("method", secondaryText(" \n\t ", CompletionItemKind.Method))
    }

    @Test
    fun `an item without a kind has no look, every LSP kind has one`() {
        assertNull(kindLook(null))
        for (kind in CompletionItemKind.values()) assertNotNull(kindLook(kind), "$kind")
    }
}
