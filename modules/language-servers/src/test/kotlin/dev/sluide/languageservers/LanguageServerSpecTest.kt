package dev.sluide.languageservers

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** What a language spec decides by default. */
class LanguageServerSpecTest {
    /** A spec that keeps every default. */
    private val plain =
        object : LanguageServerSpec {
            override val id = "plain"
            override val name = "Plain"

            override fun matches(file: Path) = true

            override fun languageId(file: Path) = "plain"

            override fun command(): ServerCommand? = null
        }

    @ParameterizedTest
    @CsvSource(
        "start, true",
        "a-b, true",
        "名前, true",
        "'foo bar', false",
        "'', false",
        "'a\tb', false",
    )
    fun `by default a name is an identifier when it is not empty and has no whitespace or control characters`(
        name: String,
        expected: Boolean,
    ) {
        assertEquals(expected, plain.isIdentifier(name))
    }

    @Test
    fun `by default the window folder serves every file, nothing is watched and no change re-resolves folders`() {
        val window = Path.of("/work/mono")
        val file = window.resolve("deployables/backend_one/app.py")

        assertNull(plain.workspaceFolder(file, window))
        assertEquals(emptyList(), plain.watchedFolders(window))
        assertFalse(plain.affectsWorkspaceFolders(window.resolve("uv.lock")))
        assertNull(plain.workspaceStatus(null, window))
        assertNull(plain.environmentProblem(file, window))
    }

    @Test
    fun `by default no document is gated, no push kept, no config file named and the status line is the folder's`() {
        val window = Path.of("/work/mono")
        val file = window.resolve("apps/a/main.ts")
        val status = WorkspaceStatus("TypeScript project: apps/a", window.resolve("apps/a"), null)
        val folderStatus =
            object : LanguageServerSpec {
                override val id = "folders"
                override val name = "Folders"

                override fun matches(file: Path) = true

                override fun languageId(file: Path) = "folders"

                override fun command(): ServerCommand? = null

                override fun workspaceStatus(
                    folder: Path?,
                    projectFolder: Path,
                ) = status
            }

        assertFalse(plain.gatesDocuments)
        assertEquals(DocumentGate.SERVED, plain.gate(file, window))
        assertFalse(plain.keepsDiagnostics(window.resolve("tsconfig.json")))
        assertEquals(emptyList(), plain.configFiles(file, window))
        assertEquals(status, folderStatus.workspaceStatus(file, window.resolve("apps/a"), window))
    }
}
