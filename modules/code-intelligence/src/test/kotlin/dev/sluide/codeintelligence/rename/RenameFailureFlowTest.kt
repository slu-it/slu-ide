package dev.sluide.codeintelligence.rename

import dev.sluide.codeintelligence.awaitTrue
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rename in a bound editor when something goes wrong or moves: a server that fails, answers nothing or answers late
 * after the editor closed, a file bound meanwhile, a new rename that drops the one on its way, where the name and the
 * caret are asked and left, and the messages of a rename that did not apply.
 */
internal class RenameFailureFlowTest : RenameFlowFixture() {
    @Test
    fun `a prepareRename the server fails says why at the name, unless the text changed`() {
        setUp(caret = 22)
        server.prepareAnswer = { throw failure("no index") }
        startRename()
        awaitTrue("note") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(listOf(22 to "Cannot rename this: no index"), fx.onFx { view.notes })

        server.prepareAnswer = { throw failure("") }
        startRename()
        awaitTrue("second note") { fx.onFx { view.notes.size == 2 } }
        assertEquals(22 to "Cannot rename this", fx.onFx { view.notes[1] })

        server.prepareAnswer = {
            fx.onFx { editor.edit(0, 0, "# x\n") }
            throw failure("late")
        }
        fx.onFx { editor.caret = 26 }
        startRename()
        awaitTrue("third note") { fx.onFx { view.notes.size == 3 } }
        assertEquals(26 to "The text changed. Rename again.", fx.onFx { view.notes[2] })
    }

    @Test
    fun `a prepareRename failing after the editor closed shows nothing`() {
        setUp(caret = 22)
        server.prepareAnswer = {
            fx.onFx { closeBinding() }
            throw failure("no index")
        }
        startRename()
        serverIdle()
        assertEquals(emptyList(), fx.onFx { view.notes })
    }

    @Test
    fun `a prepareRename answered after the editor closed asks for no name`() {
        setUp(caret = 22)
        server.prepareAnswer = {
            fx.onFx { closeBinding() }
            Either3.forFirst(range(1, 11, 14))
        }
        startRename()
        serverIdle()
        assertEquals(emptyList(), fx.onFx { view.notes })
        assertNull(fx.onFx { view.asked })
    }

    @Test
    fun `a rename the server fails is told, one it answers nothing or no file to says nothing to rename`() {
        setUp(caret = 22, prepares = false)
        server.renameAnswer = { throw failure("no index") }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("told") { fx.onFx { view.told.isNotEmpty() } }
        assertEquals(listOf("Rename failed: no index"), fx.onFx { view.told })

        server.renameAnswer = { null }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("note") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(listOf(22 to "Nothing to rename"), fx.onFx { view.notes })

        server.renameAnswer = { WorkspaceEdit(emptyMap()) }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("second note") { fx.onFx { view.notes.size == 2 } }
        assertEquals(22 to "Nothing to rename", fx.onFx { view.notes[1] })
        assertEquals(text, fx.onFx { editor.text })
    }

    @Test
    fun `a rename answered after the editor closed changes and shows nothing`() {
        setUp(caret = 22, prepares = false)
        server.renameAnswer = {
            fx.onFx { closeBinding() }
            edit(file to ownEdits(it.newName))
        }
        startRename()
        fx.onFx { view.answer("start") }
        serverIdle()
        assertEquals(text, fx.onFx { editor.text })
        assertEquals(emptyList(), fx.onFx { view.notes })
        assertEquals(emptyList(), fx.onFx { view.told })
    }

    @Test
    fun `an editor closed while the preview looks at the files drops the rename`() {
        setUp(caret = 22, prepares = false)
        other.writeText("run()\n")
        fileStates = mapOf(file to RenameFileState.OPEN, other to RenameFileState.CLOSED)
        server.renameAnswer =
            { edit(file to ownEdits(it.newName), other to listOf(TextEdit(range(0, 0, 3), it.newName))) }
        statesHook = { closeBinding() }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("states asked") { statesAsked.isNotEmpty() }
        serverIdle()
        assertEquals(emptyList(), fx.onFx { view.previews }, "no preview of a closed editor")
        assertEquals("run()\n", other.readText())
        assertEquals(emptyList(), fx.onFx { view.notes }, "and no note about it")
    }

    @Test
    fun `an editor closed while the preview shows applies nothing, though it was confirmed`() {
        setUp(caret = 22, prepares = false)
        other.writeText("run()\n")
        fileStates = mapOf(file to RenameFileState.OPEN, other to RenameFileState.CLOSED)
        server.renameAnswer =
            { edit(file to ownEdits(it.newName), other to listOf(TextEdit(range(0, 0, 3), it.newName))) }
        view.onConfirm = { closeBinding() }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("previewed") { fx.onFx { view.previews.isNotEmpty() } }
        serverIdle()
        assertEquals("run()\n", other.readText())
        assertEquals(text, fx.onFx { editor.text })
        assertEquals(emptyList(), fx.onFx { view.notes })
    }

    @Test
    fun `a file bound while the server answered drops the rename like one that changed`() {
        setUp(caret = 22, prepares = false)
        other.writeText("from a import run\n")
        server.renameAnswer = {
            bindOther("from a import run\n")
            edit(file to ownEdits(it.newName), other to listOf(TextEdit(range(0, 14, 17), it.newName)))
        }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("note") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(listOf(22 to "The text changed. Rename again."), fx.onFx { view.notes })
        assertEquals(text, fx.onFx { editor.text })
    }

    @Test
    fun `a new rename drops the answer of the one on its way`() {
        setUp(caret = 22, prepares = false)
        val release = CountDownLatch(1)
        server.renameAnswer = {
            check(release.await(5, TimeUnit.SECONDS)) { "not released" }
            edit(file to ownEdits(it.newName))
        }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("on its way") { server.renameRequests.size == 1 }
        startRename()
        release.countDown()
        serverIdle()
        assertEquals(text, fx.onFx { editor.text }, "the first one's edit is not applied")
        assertEquals(2, fx.onFx { view.askCount })
    }

    @Test
    fun `a rename started after the editor closed does nothing`() {
        setUp(caret = 22)
        val item = fx.onFx { renameItem() }
        fx.onFx { closeBinding() }
        fx.onFx { item.onAction.handle(null) }
        serverIdle()
        assertEquals(emptyList(), server.prepareRequests)
        assertEquals(emptyList(), fx.onFx { view.notes })
        assertNull(fx.onFx { view.asked })
        assertTrue(view.closed, "closing the editor closed its popups")
    }

    @Test
    fun `a name typed after the editor closed asks the server nothing`() {
        setUp(caret = 22, prepares = false)
        startRename()
        fx.onFx { closeBinding() }
        fx.onFx { view.answer("start") }
        serverIdle()
        assertEquals(emptyList(), server.renameRequests)
        assertEquals(listOf(22 to "The text changed. Rename again."), fx.onFx { view.notes })
    }

    @Test
    fun `a rename that fails after the editor closed tells nothing`() {
        setUp(caret = 22, prepares = false)
        fileStates = mapOf(file to RenameFileState.OPEN, other to RenameFileState.CLOSED)
        server.renameAnswer = {
            edit(file to ownEdits(it.newName), other to listOf(TextEdit(range(0, 0, 3), it.newName)))
        }
        fxHook = { fx.onFx { closeBinding() } }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("applier ran") { fx.onFx { view.previews.isNotEmpty() } }
        serverIdle()
        assertEquals(emptyList(), fx.onFx { view.told })
    }

    @Test
    fun `the name is asked at its start from its end, at the caret inside it, between names at the caret`() {
        setUp(caret = 25)
        server.prepareAnswer = { Either3.forThird(PrepareRenameDefaultBehavior(true)) }
        startRename()
        awaitTrue("end") { server.prepareRequests.size == 1 }
        assertEquals(Position(1, 11), server.prepareRequests[0].position)

        fx.onFx { editor.caret = 24 }
        startRename()
        awaitTrue("inside") { server.prepareRequests.size == 2 }
        assertEquals(Position(1, 13), server.prepareRequests[1].position)

        fx.onFx { editor.caret = 26 }
        startRename()
        awaitTrue("between") { server.prepareRequests.size == 3 }
        assertEquals(Position(2, 0), server.prepareRequests[2].position)
        awaitTrue("no name") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(listOf(26 to "Cannot rename this"), fx.onFx { view.notes })
    }

    @Test
    fun `a placeholder is offered when it differs, an empty one is not, and an empty range is refused`() {
        setUp(caret = 22)
        server.prepareAnswer = { Either3.forSecond(PrepareRenameResult(range(1, 11, 14), "runner")) }
        startRename()
        awaitTrue("asked") { fx.onFx { view.asked != null } }
        assertEquals((22 until 25) to "runner", fx.onFx { view.asked })

        fx.onFx { view.asked = null }
        server.prepareAnswer = { Either3.forSecond(PrepareRenameResult(range(1, 11, 14), "")) }
        startRename()
        awaitTrue("asked again") { fx.onFx { view.asked != null } }
        assertEquals((22 until 25) to "run", fx.onFx { view.asked })

        fx.onFx { view.asked = null }
        server.prepareAnswer = { Either3.forFirst(range(1, 11, 11)) }
        startRename()
        awaitTrue("refused") { fx.onFx { view.notes.isNotEmpty() } }
        assertEquals(listOf(22 to "Cannot rename this"), fx.onFx { view.notes })
        assertNull(fx.onFx { view.asked })
    }

    @Test
    fun `a name that ends the document is renamed`() {
        setUp(caret = 7, prepares = false, docText = "x = run")
        startRename()
        assertEquals((4 until 7) to "run", fx.onFx { view.asked })
    }

    @Test
    fun `the rename is asked at the name's place, the caret keeps its place in the new name`() {
        setUp(caret = 24, prepares = false)
        server.renameAnswer = { edit(file to ownEdits(it.newName)) }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("renamed") { fx.onFx { editor.text } == "def start():\n    return start\n\nstart()\n" }
        assertEquals(Position(1, 13), server.renameRequests.single().position)
        assertEquals(listOf(26), fx.onFx { editor.caretMoves }, "two characters into the new name, moved once")
    }

    @Test
    fun `a caret outside the renamed name goes to its start or end`() {
        setUp(caret = 28)
        server.prepareAnswer = { Either3.forFirst(range(1, 11, 14)) }
        server.renameAnswer = { edit(file to ownEdits(it.newName)) }
        startRename()
        awaitTrue("asked") { fx.onFx { view.asked != null } }
        fx.onFx { view.answer("start") }
        awaitTrue("renamed") { fx.onFx { editor.text } == "def start():\n    return start\n\nstart()\n" }
        assertEquals(listOf(27), fx.onFx { editor.caretMoves }, "after the name's start, as far in as its length")
    }

    @Test
    fun `a caret before the renamed name goes to its start`() {
        setUp(caret = 0)
        server.prepareAnswer = { Either3.forFirst(range(1, 11, 14)) }
        server.renameAnswer = { edit(file to ownEdits(it.newName)) }
        startRename()
        awaitTrue("asked") { fx.onFx { view.asked != null } }
        fx.onFx { view.answer("go") }
        awaitTrue("renamed") { fx.onFx { editor.text } == "def go():\n    return go\n\ngo()\n" }
        assertEquals(listOf(21), fx.onFx { editor.caretMoves })
    }

    @Test
    fun `other files only are renamed without moving the caret`() {
        setUp(caret = 22, prepares = false)
        other.writeText("from a import run\n")
        fileStates = mapOf(other to RenameFileState.CLOSED)
        server.renameAnswer = { edit(other to listOf(TextEdit(range(0, 14, 17), it.newName))) }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("written") { other.readText() == "from a import start\n" }
        assertEquals(emptyList(), fx.onFx { editor.caretMoves })
        assertEquals(text, fx.onFx { editor.text })
    }

    @Test
    fun `with several files the caret moves once, for this editor only`() {
        setUp(caret = 22, prepares = false)
        val second = bindOther("from a import run\n")
        fileStates = mapOf(file to RenameFileState.OPEN, other to RenameFileState.OPEN)
        server.renameAnswer = {
            edit(file to ownEdits(it.newName), other to listOf(TextEdit(range(0, 14, 17), it.newName)))
        }
        startRename()
        fx.onFx { view.answer("go") }
        awaitTrue("both renamed") { fx.onFx { second.text } == "from a import go\n" }
        awaitTrue("own renamed") { fx.onFx { editor.text }.startsWith("def go") }
        assertEquals(listOf(22 - 1), fx.onFx { editor.caretMoves }, "3 characters became 2 before the name")
        assertEquals(emptyList(), fx.onFx { second.caretMoves })
    }

    @Test
    fun `an editor that refuses its edits keeps its caret and the failure is told`() {
        setUp(caret = 22, prepares = false, newEditor = ::RefusingEditor)
        server.renameAnswer = { edit(file to ownEdits(it.newName)) }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("told") { fx.onFx { view.told.isNotEmpty() } }
        assertEquals(emptyList(), fx.onFx { editor.caretMoves })
        assertEquals(text, fx.onFx { editor.text })
    }

    @Test
    fun `a file the preview did not find refuses the whole rename, nothing is applied`() {
        setUp(caret = 22, prepares = false)
        fileStates = mapOf(file to RenameFileState.OPEN, other to RenameFileState.CLOSED)
        server.renameAnswer = {
            edit(file to ownEdits(it.newName), other to listOf(TextEdit(range(0, 0, 3), it.newName)))
        }
        startRename()
        fx.onFx { view.answer("start") }
        awaitTrue("told") { fx.onFx { view.told.isNotEmpty() } }
        assertEquals(listOf("Rename not applied: b.py: does not exist."), fx.onFx { view.told })
        assertEquals(text, fx.onFx { editor.text })
    }
}
