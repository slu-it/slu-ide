package dev.sluide.codeintelligence.symbols

import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.awaitTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The file structure popup over a fake server: waiting for the answer, cancels, failures, selection, closing.
 */
internal class FileStructureFlowTest : SymbolFlowBase() {
    @Test
    fun `typing in the structure popup before it showed anything lists nothing`() {
        setUp(caret = 2)
        fx.onFx { structureView.type("x") }
        assertEquals(emptyList(), fx.onFx { structureView.items })
    }

    @Test
    fun `the structure popup lists nothing and says it searches until the answer, also when it opens again`() {
        setUp(caret = 37)
        server.documentSymbols = tree
        server.holdDocumentSymbols = true
        fx.onFx { intelligence.fileStructure() }
        awaitTrue("the request") { server.heldDocumentSymbols.size == 1 }
        fx.onFx {
            assertEquals("Searching…", structureView.placeholder)
            assertEquals(emptyList(), structureView.items)
        }
        server.heldDocumentSymbols[0].complete(answerDocumentSymbols())
        awaitTrue("symbols") { fx.onFx { structureView.items.isNotEmpty() } }
        fx.onFx { structureView.hide() }

        fx.onFx { intelligence.fileStructure() }
        awaitTrue("the second request") { server.heldDocumentSymbols.size == 2 }
        fx.onFx {
            assertEquals("Searching…", structureView.placeholder)
            assertEquals(emptyList(), structureView.items, "the rows of the first time are gone")
            structureView.type("m")
            assertEquals(emptyList(), structureView.items, "typing finds nothing in them either")
        }
    }

    @Test
    fun `hiding the structure popup cancels the request on its way`() {
        setUp(caret = 2)
        server.holdDocumentSymbols = true
        fx.onFx { intelligence.fileStructure() }
        awaitTrue("the request") { server.heldDocumentSymbols.size == 1 }
        fx.onFx { structureView.hide() }
        awaitTrue("the cancel") { server.heldDocumentSymbols[0].isCancelled }
    }

    @Test
    fun `closing the file structure hides its popup`() {
        setUp(caret = 2)
        fx.onFx { intelligence.fileStructure() }
        assertTrue(fx.onFx { structureView.isShowing })
        fx.onFx { structures.single().close() }
        assertFalse(fx.onFx { structureView.isShowing })
    }

    @Test
    fun `a failing structure request puts the failure in the popup`() {
        setUp(caret = 2)
        server.failure = failure("boom")
        fx.onFx { intelligence.fileStructure() }
        awaitTrue("the failure") { fx.onFx { structureView.placeholder } == "File Structure failed: boom" }
        assertEquals(emptyList(), fx.onFx { structureView.items })
    }

    @Test
    fun `without a place for the popup the structure is not asked for`() {
        setUp(caret = 2)
        server.holdDocumentSymbols = true
        structureView.hasPlace = false
        fx.onFx { intelligence.fileStructure() }
        // Requests reach the server in the order they were sent: once this one is there, a structure request
        // sent before it would be, too.
        fx.onFx { symbols.open() }
        search("sentinel")
        awaitTrue("the sentinel") { server.symbolQueries.isNotEmpty() }
        assertEquals(emptyList(), server.heldDocumentSymbols)
    }

    @Test
    fun `a typed query selects the best match, the blank one the symbol at the caret`() {
        setUp(caret = 37)
        server.documentSymbols = tree
        fx.onFx { intelligence.fileStructure() }
        awaitTrue("symbols") { fx.onFx { structureView.items.isNotEmpty() } }
        fx.onFx {
            structureView.type("n")
            assertEquals(listOf("Console", "run", "main"), structureView.items.map { it.row.name })
            assertEquals("Console", structureView.selected?.row?.name, "not the caret's symbol, which is in the list")
            assertEquals("Console", structureView.requested?.row?.name)
            structureView.type(" ")
            assertEquals("run", structureView.requested?.row?.name, "a blank query is no query")
        }
    }

    @Test
    fun `without a symbol at the caret the first one is selected`() {
        setUp(caret = 2)
        server.documentSymbols = listOf(symbol("main", 4, 6, 4), symbol("other", 8, 9, 4))
        fx.onFx { intelligence.fileStructure() }
        awaitTrue("symbols") { fx.onFx { structureView.items.isNotEmpty() } }
        assertEquals("main", fx.onFx { structureView.requested?.row?.name })
    }

    @Test
    fun `an answer for a structure popup that went away unannounced, or whose editor closed, shows nothing`() {
        setUp(caret = 2)
        fx.onFx { intelligence.extend(FakeCodeEditor(other, text, host)) }
        server.documentSymbols = tree
        server.holdDocumentSymbols = true
        val away = FakeSearchView<ShownSymbol>()
        val closedWith = FakeSearchView<ShownSymbol>()
        val first = leakedStructure(away, of = other)
        fx.onFx { first.show() }
        awaitTrue("the first request") { server.heldDocumentSymbols.size == 1 }
        val second = leakedStructure(closedWith, of = file)
        fx.onFx { second.show() }
        awaitTrue("the second request") { server.heldDocumentSymbols.size == 2 }

        fx.onFx {
            away.isShowing = false
            intelligence.attached
                .single { it.binding?.file == file }
                .close()
        }
        server.heldDocumentSymbols.forEach { it.complete(answerDocumentSymbols()) }
        awaitSentinel()

        fx.onFx {
            assertEquals(emptyList(), away.items, "the popup was not showing")
            assertEquals("Searching…", away.placeholder)
            assertEquals(emptyList(), closedWith.items, "the editor was closed")
            assertEquals("Searching…", closedWith.placeholder)
        }
    }

    @Test
    fun `a failure for a structure popup that went away unannounced shows nothing`() {
        setUp(caret = 2)
        server.holdDocumentSymbols = true
        val away = FakeSearchView<ShownSymbol>()
        val leaked = leakedStructure(away)
        fx.onFx { leaked.show() }
        awaitTrue("the request") { server.heldDocumentSymbols.size == 1 }
        fx.onFx { away.isShowing = false }
        server.heldDocumentSymbols[0].completeExceptionally(failure("boom"))
        awaitSentinel()

        assertEquals("Searching…", fx.onFx { away.placeholder })
    }

    @Test
    fun `closing the file structure cancels its request also when the popup went away unannounced`() {
        setUp(caret = 2)
        server.holdDocumentSymbols = true
        val away = FakeSearchView<ShownSymbol>()
        val leaked = leakedStructure(away)
        fx.onFx { leaked.show() }
        awaitTrue("the request") { server.heldDocumentSymbols.size == 1 }
        fx.onFx { away.isShowing = false }
        fx.onFx { leaked.close() }
        awaitTrue("the cancel") { server.heldDocumentSymbols[0].isCancelled }
    }
}
