package dev.sluide.codeintelligence.quickfix

import dev.sluide.codeintelligence.edits.WorkspaceEditResult
import dev.sluide.languageservers.ServerState
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionDisabled
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
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
    fun `entries whose command is not offered are left out, with or without an edit`() {
        val fixes =
            quickFixesOf(
                listOf(
                    Either.forRight(CodeAction("known").apply { command = Command("k", "x.known") }),
                    Either.forRight(CodeAction("unknown").apply { command = Command("u", "x.unknown") }),
                    Either.forRight(
                        CodeAction("edit and unknown").apply {
                            edit = WorkspaceEdit(emptyMap())
                            command = Command("u", "x.unknown")
                        },
                    ),
                    action("edit only"),
                    Either.forLeft(Command("bare unknown", "x.unknown")),
                    Either.forLeft(Command("bare known", "x.known")),
                ),
            ) { it == "x.known" }

        assertEquals(listOf("known", "edit only", "bare known"), fixes.map { it.title })
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

    /** A value as lsp4j parses a server's JSON: what the JSON lacks stays null. */
    private inline fun <reified T> parsed(json: String): T =
        MessageJsonHandler(emptyMap()).gson.fromJson(json, T::class.java)

    @Test
    fun `entries the JSON leaves without a title or a usable shape are left out`() {
        val fixes =
            quickFixesOf(
                listOf(
                    Either.forLeft(parsed<Command>("""{"command":"x.run"}""")),
                    Either.forRight(parsed<CodeAction>("""{"edit":{}}""")),
                    Either.forLeft(null),
                    action("kept"),
                ),
            )
        assertEquals(listOf("kept"), fixes.map { it.title })
    }

    @Test
    fun `a disabled action without a usable reason says it is not available, preferred is only true when sent`() {
        val fixes =
            quickFixesOf(
                listOf(
                    Either.forRight(parsed<CodeAction>("""{"title":"a","disabled":{}}""")),
                    Either.forRight(parsed<CodeAction>("""{"title":"b","disabled":{"reason":" "}}""")),
                    Either.forRight(parsed<CodeAction>("""{"title":"c","disabled":{"reason":"Why"}}""")),
                    Either.forRight(parsed<CodeAction>("""{"title":"d","isPreferred":false}""")),
                    Either.forRight(parsed<CodeAction>("""{"title":"e"}""")),
                    Either.forRight(parsed<CodeAction>("""{"title":"f","isPreferred":true}""")),
                ),
            )
        assertEquals(listOf("f", "d", "e", "a", "b", "c"), fixes.map { it.title })
        assertEquals(
            listOf(null, null, null, "not available", "not available", "Why"),
            fixes.map { it.disabledReason },
        )
        assertEquals(listOf(true, false, false, false, false, false), fixes.map { it.preferred })
    }

    @Test
    fun `a command the JSON leaves without a command name is asked about as empty`() {
        val asked = mutableListOf<String>()
        val answer =
            listOf(
                Either.forRight<Command, CodeAction>(
                    CodeAction("t").apply {
                        command =
                            parsed<Command>("""{"title":"c"}""")
                    },
                ),
            )
        val fixes =
            quickFixesOf(answer) {
                asked += it
                false
            }
        assertEquals(listOf(""), asked)
        assertEquals(emptyList(), fixes)
    }

    @Test
    fun `an action with an edit and no command needs no resolve`() {
        assertFalse(needsResolve(CodeAction("e").apply { edit = WorkspaceEdit(emptyMap()) }, serverResolves = true))
    }
}
