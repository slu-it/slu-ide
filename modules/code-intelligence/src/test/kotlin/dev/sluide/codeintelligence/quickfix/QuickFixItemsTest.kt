package dev.sluide.codeintelligence.quickfix

import dev.sluide.codeintelligence.edits.WorkspaceEditResult
import dev.sluide.languageservers.ServerState
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionDisabled
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The quick fix list: preferred first, disabled last with their reason, bare commands too; when to resolve or refuse. */
class QuickFixItemsTest {
    private fun action(
        title: String,
        preferred: Boolean = false,
        disabled: String? = null,
    ): Either<Command, CodeAction> =
        Either.forRight(
            CodeAction(title).apply {
                isPreferred = preferred
                if (disabled != null) this.disabled = CodeActionDisabled(disabled)
                edit = WorkspaceEdit(emptyMap())
            },
        )

    @Test
    fun `preferred actions come first, disabled ones last with their reason, each in the server's order`() {
        val fixes =
            quickFixesOf(
                listOf(
                    action("Ignore for this line"),
                    action("Cannot here", disabled = "Not in a function"),
                    action("import pathlib.Path", preferred = true),
                    Either.forLeft(Command("Run command", "x.run")),
                    null,
                    action(""),
                    action("import zipfile.Path", preferred = true),
                ),
            )
        assertEquals(
            listOf("import pathlib.Path", "import zipfile.Path", "Ignore for this line", "Run command", "Cannot here"),
            fixes.map { it.title },
        )
        assertEquals("Not in a function", fixes.last().disabledReason)
        assertFalse(fixes.last().enabled)
        assertEquals("x.run", fixes[3].command?.command)
        assertEquals(emptyList(), quickFixesOf(null))
    }

    @Test
    fun `an action without edit and command is resolved first if the server resolves`() {
        val bare = CodeAction("bare")
        assertTrue(needsResolve(bare, serverResolves = true))
        assertFalse(needsResolve(bare, serverResolves = false))
        assertFalse(
            needsResolve(CodeAction("with command").apply { command = Command("c", "c") }, serverResolves = true),
        )
    }

    @Test
    fun `quick fixes need a running server that offers code actions`() {
        assertNull(quickFixBlocker(ServerState.Running, supported = true))
        assertEquals("server not running", quickFixBlocker(ServerState.Starting, supported = true))
        assertEquals("not offered by the server", quickFixBlocker(ServerState.Running, supported = false))
    }

    @Test
    fun `a fix applied in part names the files it changed and what failed`() {
        assertEquals(
            "Quick fix not applied: a.py: read-only.",
            quickFixFailure(WorkspaceEditResult.refused("a.py: read-only.")),
        )
        assertEquals(
            "Quick fix applied to a.py, b.py only. c.py: changed on disk meanwhile.",
            quickFixFailure(
                WorkspaceEditResult(
                    listOf(Path.of("a.py"), Path.of("b.py")),
                    3,
                    listOf("c.py: changed on disk meanwhile."),
                ),
            ),
        )
    }
}
