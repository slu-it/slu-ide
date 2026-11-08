package dev.sluide.codeintelligence.edits

import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.languageservers.LspUris
import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.SnippetTextEdit
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Workspace edits over a fake window and real files: a bound editor gets its edits as one call, another open editor
 * its new text, a closed file is written atomically in its own line breaks and the servers hear of it; every file is
 * checked first, so one refused file (an old version, an edit outside its text, not UTF-8, too large) changes none.
 */
class WorkspaceEditsTest {
    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var outsideDir: Path

    private val target by lazy { FakeTarget(dir) }

    /** The file work and what it hands back to the FX thread, in order, run by the test with [runIo]. */
    private val ioTasks = ArrayDeque<Runnable>()

    private var libraryReadOnly = true

    private val edits by lazy {
        WorkspaceEdits(
            target,
            io = { ioTasks.addLast(it) },
            fx = { ioTasks.addLast(it) },
            libraryReadOnly = { libraryReadOnly },
        )
    }

    private fun runIo() {
        while (ioTasks.isNotEmpty()) ioTasks.removeFirst().run()
    }

    private fun apply(edit: WorkspaceEdit): WorkspaceEditResult? {
        var result: WorkspaceEditResult? = null
        edits.apply(edit) { result = it }
        runIo()
        return result
    }

    private fun insert(
        line: Int,
        text: String,
    ) = TextEdit(Range(Position(line, 0), Position(line, 0)), text)

    private fun documentEdit(
        file: Path,
        version: Int?,
        vararg edits: TextEdit,
    ): Either<TextDocumentEdit, ResourceOperation> =
        Either.forLeft(
            TextDocumentEdit(
                VersionedTextDocumentIdentifier(LspUris.of(file), version),
                edits.map { Either.forLeft(it) },
            ),
        )

    @Test
    fun `a bound editor gets its edits as one step, another open one its new text, a closed file is written`() {
        val bound = dir.resolve("bound.py")
        val open = dir.resolve("open.py")
        val closed = dir.resolve("closed.py")
        target.boundTexts[bound] = FakeBound(3, "a\nb\n")
        target.openTexts[open] = "x = 1\n"
        // On disk as the editor shows it, in the file's own line breaks: the server read that.
        open.writeText("x = 1\r\n")
        closed.writeText("import os\r\nprint(os)\r\n")
        val edit =
            WorkspaceEdit(
                listOf(
                    documentEdit(bound, 3, insert(1, "B"), insert(0, "A")),
                    documentEdit(open, null, insert(1, "y = 2\n")),
                    documentEdit(closed, null, insert(1, "import sys\n")),
                ),
            )

        val result = assertIs<WorkspaceEditResult>(apply(edit))

        assertEquals(emptyList(), result.failures)
        assertEquals(listOf(bound, open, closed), result.files)
        assertEquals(4, result.edits)
        assertEquals(
            listOf(listOf(OffsetEdit(0, 0, "A"), OffsetEdit(2, 2, "B"))),
            target.boundTexts.getValue(bound).applied,
        )
        assertEquals("x = 1\ny = 2\n", target.openTexts[open])
        assertEquals("import os\r\nimport sys\r\nprint(os)\r\n", closed.readText(), "the file keeps CRLF")
        assertEquals(listOf(listOf(closed)), target.changed)
        val left = Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList() }
        assertEquals(setOf("closed.py", "open.py"), left.toSet(), "no temporary file left")
    }

    @Test
    fun `typing in a bound editor while the closed files are read refuses versionless edits, also the closed ones`() {
        val bound = dir.resolve("bound.py")
        val closed = dir.resolve("closed.py")
        target.boundTexts[bound] = FakeBound(3, "a\nb\n")
        closed.writeText("c\n")
        var result: WorkspaceEditResult? = null
        edits.apply(changesOf(bound to insert(1, "B"), closed to insert(0, "C"))) { result = it }
        ioTasks.removeFirst().run() // read and checked
        target.boundTexts.getValue(bound).apply {
            text = "x\na\nb\n"
            version = 4
        }
        runIo()

        assertEquals(listOf("bound.py changed meanwhile."), result?.failures)
        assertEquals(emptyList(), target.boundTexts.getValue(bound).applied)
        assertEquals("c\n", closed.readText())
    }

    @Test
    fun `another open editor changed, or a closed file opened while the files are read refuses the edit`() {
        val open = dir.resolve("open.py")
        val closed = dir.resolve("closed.py")
        target.openTexts[open] = "o\n"
        open.writeText("o\n")
        closed.writeText("c\n")
        var result: WorkspaceEditResult? = null
        edits.apply(changesOf(open to insert(0, "O"), closed to insert(0, "C"))) { result = it }
        ioTasks.removeFirst().run()
        target.openTexts[open] = "typed\no\n"
        runIo()
        assertEquals(listOf("open.py changed meanwhile."), result?.failures)
        assertEquals("typed\no\n", target.openTexts[open])

        target.openTexts[open] = "o\n"
        edits.apply(changesOf(open to insert(0, "O"), closed to insert(0, "C"))) { result = it }
        ioTasks.removeFirst().run()
        target.openTexts[closed] = "c\n"
        runIo()
        assertEquals(listOf("closed.py was opened meanwhile."), result?.failures)
        assertEquals("o\n", target.openTexts[open])
        assertEquals("c\n", closed.readText())
    }

    @Test
    fun `a version for a file that is not bound refuses, open or closed`() {
        val open = dir.resolve("open.py")
        val closed = dir.resolve("closed.py")
        target.openTexts[open] = "o\n"
        open.writeText("o\n")
        closed.writeText("c\n")
        assertEquals(
            listOf("open.py is no longer open as the server knows it."),
            apply(WorkspaceEdit(listOf(documentEdit(open, 2, insert(0, "x")))))?.failures,
        )
        assertEquals(
            listOf("closed.py is no longer open as the server knows it."),
            apply(WorkspaceEdit(listOf(documentEdit(closed, 2, insert(0, "x")))))?.failures,
        )
        assertEquals("o\n", target.openTexts[open])
        assertEquals("c\n", closed.readText())
    }

    @Test
    fun `an open editor with unsaved changes, a read-only file and a file outside the project are refused`() {
        val open = dir.resolve("open.py")
        target.openTexts[open] = "unsaved\n"
        open.writeText("saved\n")
        assertEquals(
            listOf("open.py has unsaved changes the server does not know."),
            apply(changesOf(open to insert(0, "x")))?.failures,
        )
        assertEquals("unsaved\n", target.openTexts[open])

        // Refused before anything changes: the open editor keeps its text.
        open.writeText("unsaved\n")
        val readOnly = dir.resolve("read-only.py")
        readOnly.writeText("r\n")
        assertTrue(readOnly.toFile().setWritable(false))
        assumeFalse(Files.isWritable(readOnly), "root ignores file modes")
        assertEquals(
            listOf("read-only.py: read-only."),
            apply(changesOf(open to insert(0, "x"), readOnly to insert(0, "x")))?.failures,
        )
        assertEquals("unsaved\n", target.openTexts[open])
        assertEquals("r\n", readOnly.readText())

        // Made read-only after it was read: the atomic move would replace it anyway.
        val late = dir.resolve("late.py")
        late.writeText("l\n")
        var result: WorkspaceEditResult? = null
        edits.apply(changesOf(late to insert(0, "x"))) { result = it }
        ioTasks.removeFirst().run()
        assertTrue(late.toFile().setWritable(false))
        assumeFalse(Files.isWritable(late), "root ignores file modes")
        runIo()
        assertEquals(listOf("late.py: read-only."), result?.failures)
        assertEquals("l\n", late.readText())

        val outside = outsideDir.resolve("other.py")
        outside.writeText("o\n")
        assertEquals(listOf("other.py: outside the project."), apply(changesOf(outside to insert(0, "x")))?.failures)
        assertEquals("o\n", outside.readText())
    }

    @Test
    fun `a closed library file in the project is refused while library code is read-only, written otherwise`() {
        val venv = dir.resolve(".venv")
        Files.createDirectories(venv)
        venv.resolve("pyvenv.cfg").writeText("home = /usr/bin\n")
        val library = venv.resolve("lib.py")
        library.writeText("l\n")
        val ordinary = dir.resolve("ordinary.py")
        ordinary.writeText("o\n")

        assertEquals(
            listOf("lib.py: library code (virtual environment .venv)."),
            apply(changesOf(library to insert(0, "x"), ordinary to insert(0, "x")))?.failures,
        )
        assertEquals("l\n", library.readText())
        assertEquals("o\n", ordinary.readText(), "refused before anything is written")

        assertEquals(emptyList(), apply(changesOf(ordinary to insert(0, "x")))?.failures)
        assertEquals("xo\n", ordinary.readText())

        libraryReadOnly = false
        assertEquals(emptyList(), apply(changesOf(library to insert(0, "x")))?.failures)
        assertEquals("xl\n", library.readText())
    }

    @Test
    fun `a link to a library folder in the project is library code too`() {
        val inner = dir.resolve(".venv")
        Files.createDirectories(inner.resolve("lib"))
        inner.resolve("pyvenv.cfg").writeText("home = /usr/bin\n")
        val library = inner.resolve("lib/pkg.py")
        library.writeText("l\n")
        try {
            Files.createSymbolicLink(dir.resolve("alias"), inner.resolve("lib"))
        } catch (_: UnsupportedOperationException) {
            assumeFalse(true, "no symbolic links")
        }

        assertEquals(
            listOf("pkg.py: library code (virtual environment .venv)."),
            apply(changesOf(dir.resolve("alias/pkg.py") to insert(0, "x")))?.failures,
        )
        assertEquals("l\n", library.readText())
    }

    private fun changesOf(vararg edits: Pair<Path, TextEdit>) =
        WorkspaceEdit(edits.associate { (file, edit) -> LspUris.of(file) to listOf(edit) })

    @Test
    fun `an edit for an older version of a bound document refuses the whole edit, also its closed files`() {
        val bound = dir.resolve("bound.py")
        val closed = dir.resolve("closed.py")
        target.boundTexts[bound] = FakeBound(4, "a\n")
        closed.writeText("b\n")
        val edit =
            WorkspaceEdit(listOf(documentEdit(closed, null, insert(0, "x")), documentEdit(bound, 3, insert(0, "y"))))

        val result = assertIs<WorkspaceEditResult>(apply(edit))

        assertEquals(listOf("bound.py changed since the server made this edit."), result.failures)
        assertEquals(emptyList(), result.files)
        assertEquals(emptyList(), target.boundTexts.getValue(bound).applied)
        assertEquals("b\n", closed.readText())
        assertEquals(emptyList(), target.changed)
    }

    @Test
    fun `an edit outside a closed file's text refuses the open files' edits too`() {
        val open = dir.resolve("open.py")
        val closed = dir.resolve("closed.py")
        target.openTexts[open] = "x\n"
        open.writeText("x\n")
        closed.writeText("one line\n")
        val edit =
            WorkspaceEdit(
                mapOf(
                    LspUris.of(open) to listOf(insert(0, "y")),
                    LspUris.of(closed) to listOf(insert(7, "z")),
                ),
            )

        val result = assertIs<WorkspaceEditResult>(apply(edit))

        assertEquals(listOf("closed.py: an edit outside the text."), result.failures)
        assertEquals("x\n", target.openTexts[open])
        assertEquals("one line\n", closed.readText())
    }

    @Test
    fun `a closed file keeps its permissions and its BOM, and one changed on disk meanwhile is not written`() {
        val kept = dir.resolve("kept.py")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        kept.writeBytes(bom + "a\n".toByteArray())
        val posix = Files.getFileStore(dir).supportsFileAttributeView("posix")
        if (posix) Files.setPosixFilePermissions(kept, PosixFilePermissions.fromString("rwxr-x---"))
        val result =
            assertIs<WorkspaceEditResult>(
                apply(
                    WorkspaceEdit(
                        mapOf(LspUris.of(kept) to listOf(insert(1, "b\n"))),
                    ),
                ),
            )
        assertTrue(result.applied)
        assertEquals((bom + "a\nb\n".toByteArray()).toList(), kept.readBytes().toList())
        if (posix) assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(kept)))

        val raced = dir.resolve("raced.py")
        raced.writeText("a\n")
        var late: WorkspaceEditResult? = null
        edits.apply(WorkspaceEdit(mapOf(LspUris.of(raced) to listOf(insert(0, "x"))))) { late = it }
        ioTasks.removeFirst().run() // read and checked
        raced.writeText("changed\n")
        runIo()
        assertEquals(listOf("raced.py: changed on disk meanwhile."), late?.failures)
        assertEquals("changed\n", raced.readText())
    }

    @Test
    fun `closed files that are not UTF-8, too large or missing are refused`() {
        val latin = dir.resolve("latin.py")
        latin.writeBytes(byteArrayOf('a'.code.toByte(), 0xE4.toByte(), '\n'.code.toByte()))
        assertEquals(listOf("latin.py: not UTF-8."), apply(changeOf(latin))?.failures)

        val large = dir.resolve("large.py")
        RandomAccessFile(large.toFile(), "rw").use { it.setLength(MAX_CLOSED_FILE_BYTES + 1) }
        assertEquals(listOf("large.py: larger than 10 MiB."), apply(changeOf(large))?.failures)

        assertEquals(listOf("gone.py: does not exist."), apply(changeOf(dir.resolve("gone.py")))?.failures)
    }

    private fun changeOf(file: Path) = WorkspaceEdit(mapOf(LspUris.of(file) to listOf(insert(0, "x"))))

    @Test
    fun `document changes win over changes, and file operations, snippets and other URIs are refused`() {
        val file = dir.resolve("a.py")
        val both =
            WorkspaceEdit(listOf(documentEdit(file, 1, insert(0, "x")))).apply {
                changes = mapOf(LspUris.of(dir.resolve("b.py")) to listOf(insert(0, "y")))
            }
        assertEquals(PlannedEdit.Files(listOf(FileEdits(file, 1, listOf(insert(0, "x"))))), planOf(both))

        val twice =
            WorkspaceEdit(listOf(documentEdit(file, 1, insert(0, "x")), documentEdit(file, 2, insert(0, "y"))))
        assertEquals(PlannedEdit.Refused("Several edits of a.py are not supported."), planOf(twice))

        val create =
            WorkspaceEdit(listOf(Either.forRight<TextDocumentEdit, ResourceOperation>(CreateFile(LspUris.of(file)))))
        assertEquals(PlannedEdit.Refused("Creating, renaming and deleting files is not supported yet."), planOf(create))

        val snippet =
            TextDocumentEdit(
                VersionedTextDocumentIdentifier(LspUris.of(file), null),
                listOf(Either.forRight(SnippetTextEdit())),
            )
        assertIs<PlannedEdit.Refused>(planOf(WorkspaceEdit(listOf(Either.forLeft(snippet)))))
        assertEquals(
            PlannedEdit.Refused("untitled:1 is no local file"),
            planOf(WorkspaceEdit(mapOf("untitled:1" to listOf(insert(0, "x"))))),
        )
        assertNull((planOf(WorkspaceEdit(emptyMap())) as PlannedEdit.Files).files.firstOrNull())
    }

    private class FakeBound(
        override var version: Int,
        override var text: String,
    ) : BoundText {
        val applied = mutableListOf<List<OffsetEdit>>()

        override fun apply(edits: List<OffsetEdit>): Boolean {
            applied += edits
            return true
        }
    }

    private class FakeTarget(
        override val projectFolder: Path,
    ) : WorkspaceEditTarget {
        val boundTexts = mutableMapOf<Path, FakeBound>()
        val openTexts = mutableMapOf<Path, String>()
        val changed = mutableListOf<List<Path>>()

        override fun bound(file: Path): BoundText? = boundTexts[file]

        override fun openText(file: Path): String? = openTexts[file]

        override fun editOpenText(
            file: Path,
            edit: (String) -> String,
        ): Boolean {
            openTexts[file] = edit(openTexts[file] ?: return false)
            return true
        }

        override fun filesChanged(files: List<Path>) {
            changed += files
        }
    }
}
