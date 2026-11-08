package dev.sluide.codeintelligence.completion

import org.eclipse.lsp4j.CompletionItemKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
}
