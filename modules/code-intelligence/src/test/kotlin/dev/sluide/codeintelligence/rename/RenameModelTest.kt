package dev.sluide.codeintelligence.rename

import dev.sluide.codeintelligence.edits.WorkspaceEditResult
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.languageservers.LspUris
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** The decisions of rename without a server: the prepare answer, names that do nothing, counts, messages, preview. */
class RenameModelTest {
    private val folder = Path.of("/work/project").toAbsolutePath()
    private val range = Range(Position(1, 4), Position(1, 7))

    @Test
    fun `a prepare answer is a range, a range with its placeholder, the word at the caret or nothing`() {
        assertEquals(PrepareAnswer.At(range, null), prepareAnswer(Either3.forFirst(range)))
        assertEquals(
            PrepareAnswer.At(range, "run"),
            prepareAnswer(Either3.forSecond(PrepareRenameResult(range, "run"))),
        )
        assertEquals(PrepareAnswer.WordAtCaret, prepareAnswer(Either3.forThird(PrepareRenameDefaultBehavior(true))))
        assertEquals(PrepareAnswer.Refused, prepareAnswer(Either3.forThird(PrepareRenameDefaultBehavior(false))))
        assertEquals(PrepareAnswer.Refused, prepareAnswer(null))
    }

    @ParameterizedTest
    @CsvSource(
        "run, start, start",
        "run, '  start ', start",
        "run, run, ",
        "run, ' run ', ",
        "run, '', ",
        "run, '   ', ",
        "run, Run, Run",
    )
    fun `an empty or unchanged name does nothing, others are taken trimmed`(
        old: String,
        typed: String,
        wanted: String?,
    ) {
        assertEquals(wanted, wantedName(old, typed))
    }

    @Test
    fun `the edits are counted per file in the server's order, from changes or document changes`() {
        val a = folder.resolve("a.py")
        val b = folder.resolve("pkg/b.py")
        val edit = TextEdit(range, "start")
        val changes =
            WorkspaceEdit(linkedMapOf(LspUris.of(b) to listOf(edit), LspUris.of(a) to listOf(edit, edit, edit)))
        assertEquals(
            listOf(b to 1, a to 3),
            (renameChanges(changes) as RenameChanges.Files).files.map { it.file to it.count },
        )
        val documentChanges =
            WorkspaceEdit(
                listOf(
                    Either.forLeft(
                        TextDocumentEdit(
                            VersionedTextDocumentIdentifier(LspUris.of(a), 3),
                            listOf(Either.forLeft(edit), Either.forLeft(edit)),
                        ),
                    ),
                ),
            )
        assertEquals(
            listOf(a to 2),
            (renameChanges(documentChanges) as RenameChanges.Files).files.map {
                it.file to
                    it.count
            },
        )
        val twice =
            WorkspaceEdit(listOf(documentChanges.documentChanges.single(), documentChanges.documentChanges.single()))
        assertEquals(RenameChanges.Refused("Several edits of a.py are not supported."), renameChanges(twice))
    }

    @Test
    fun `a failure names what was not applied, and what was when only some files changed`() {
        assertEquals(
            "Rename not applied: b.py changed meanwhile.",
            renameFailure(WorkspaceEditResult.refused("b.py changed meanwhile.")),
        )
        assertEquals(
            "Renamed in a.py only. b.py: read-only.",
            renameFailure(WorkspaceEditResult(listOf(folder.resolve("a.py")), 3, listOf("b.py: read-only."))),
        )
    }

    @Test
    fun `the preview counts the changes, lists the files by path with their notes and names what refuses it`() {
        val a = folder.resolve("a.py")
        val b = folder.resolve("pkg/b.py")
        val c = folder.resolve("c.py")
        val stub = Path.of("/opt/stubs/lib.pyi").toAbsolutePath()
        val edit = TextEdit(range, "start")
        val changes =
            listOf(FileChanges(c, listOf(edit)), FileChanges(a, listOf(edit, edit)), FileChanges(b, listOf(edit)))
        val states = mapOf(a to RenameFileState.UNSAVED, b to RenameFileState.CLOSED, c to RenameFileState.OPEN)

        val preview = renamePreview("run", "start", changes, states, folder)
        assertEquals("Rename “run” to “start”?", preview.header)
        assertEquals("4 changes in 3 files", preview.summary)
        assertEquals(
            listOf(
                PreviewRow("a.py", 2, "unsaved changes"),
                PreviewRow("c.py", 1, null),
                PreviewRow("pkg/b.py", 1, "written to disk"),
            ),
            preview.rows,
        )
        assertEquals(null, preview.refusal, "unsaved changes the server knows are changed in the editor")
        assertEquals("a.py · 2 changes · unsaved changes", rowText(preview.rows.first()))

        val refused =
            renamePreview(
                "run",
                "start",
                listOf(FileChanges(b, listOf(edit)), FileChanges(stub, listOf(edit))),
                mapOf(b to RenameFileState.UNSAVED_UNKNOWN, stub to RenameFileState.OUTSIDE),
                folder,
            )
        assertEquals("2 changes in 2 files", refused.summary)
        assertEquals(stub.toString(), refused.rows.first().path, "outside the project absolute")
        assertEquals(
            "The rename cannot be applied: b.py has unsaved changes the server does not know; lib.pyi is outside the " +
                "project.",
            refused.refusal,
        )
        assertEquals(
            "1 change in 1 file",
            renamePreview("a", "b", listOf(FileChanges(c, listOf(edit))), emptyMap(), folder).summary,
        )
    }

    @Test
    fun `the caret stays as far into the new name, which moves by the edits before it`() {
        val edits =
            listOf(OffsetEdit(4, 7, "start"), OffsetEdit(22, 25, "start"), OffsetEdit(30, 33, "start"))
        assertEquals(24 + 1, caretAfterRename(edits, nameStart = 22, caretInName = 1, newLength = 5))
        assertEquals(4 + 3, caretAfterRename(edits, nameStart = 4, caretInName = 3, newLength = 5))
        assertEquals(24 + 2, caretAfterRename(edits, nameStart = 22, caretInName = 3, newLength = 2), "at most its end")
    }
}
