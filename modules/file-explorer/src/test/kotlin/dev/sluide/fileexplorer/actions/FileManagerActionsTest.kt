package dev.sluide.fileexplorer.actions

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileManagerActionsTest {
    @TempDir
    lateinit var root: Path

    private val errors = mutableListOf<String>()
    private val launched = mutableListOf<List<String>>()
    private val background = mutableListOf<Runnable>()
    private val dispatched = mutableListOf<Runnable>()
    private val terminals = mutableListOf<Path>()
    private var project: Path? = null
    private var terminalOpener: ((Path) -> Unit)? = { terminals.add(it) }

    private fun actions(revealCommands: (Path) -> List<List<String>> = { listOf(listOf("reveal", it.toString())) }) =
        FileManagerActions(
            projectFolder = { project },
            showError = { errors += it },
            runInBackground = { background += it },
            dispatch = { dispatched += it },
            openInTerminal = { terminalOpener },
        ).also {
            it.fileManager =
                SystemFileManager(
                    "Open it",
                    "Fake",
                    "Reveal it",
                    { path -> listOf("open", path.toString()) },
                    revealCommands,
                )
            it.launchCommand = { command -> launched += command }
        }

    @Test
    fun `the label is the reveal label for a file and the open label for a folder`() {
        val actions = actions()

        assertEquals("Reveal it", actions.label(reveal = true))
        assertEquals("Open it", actions.label(reveal = false))
    }

    @Test
    fun `by default the file manager of the system starts real programs and fails with an IOException`() {
        val actions = FileManagerActions({ null }, {}, {}, {}, { null })

        assertEquals(systemFileManager().name, actions.fileManager.name)
        // A program that does not exist: launch and launchFirstSuccess report it by an IOException, as the real tools do.
        assertFailsWith<IOException> { actions.launchCommand(listOf("/no/such/program")) }
        assertNotNull(actions.launchFirst(listOf(listOf("/no/such/program"))))
    }

    @Test
    fun `a folder is opened with the command of the file manager`() {
        val folder = root.resolve("a b").createDirectories()

        actions().open(folder)

        assertEquals(listOf(listOf("open", folder.toString())), launched)
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `the empty area opens the project folder, and nothing opens without one`() {
        val actions = actions()
        actions.open(null)
        assertEquals(emptyList(), launched)

        project = root
        actions.open(null)

        assertEquals(listOf(listOf("open", root.toString())), launched)
    }

    @Test
    fun `the empty area opens the project folder also when that is a file, without revealing it`() {
        val single = root.resolve("single.txt").createFile()
        project = single

        actions().open(null)

        assertEquals(listOf(listOf("open", single.toString())), launched)
    }

    @Test
    fun `a file is revealed with its one command, started at once`() {
        val file = root.resolve("a.txt").createFile()

        actions().open(file)

        assertEquals(listOf(listOf("reveal", file.toString())), launched)
        assertEquals(emptyList(), background)
    }

    @Test
    fun `an open that cannot start says which path and file manager, with the reason`() {
        val folder = root.resolve("dir").createDirectories()
        val actions = actions()
        actions.launchCommand = { throw IOException("no such program") }

        actions.open(folder)

        assertEquals(listOf("Could not open $folder in Fake: no such program"), errors)
    }

    @Test
    fun `a reveal with one command that cannot start says which file and file manager, with the reason`() {
        val file = root.resolve("a.txt").createFile()
        val actions = actions()
        actions.launchCommand = { throw IOException("gone") }

        actions.open(file)

        assertEquals(listOf("Could not open $file in Fake: gone"), errors)
        assertEquals(emptyList(), background)
    }

    @Test
    fun `several reveal commands run in the background, the failure of the last is shown on the FX thread`() {
        val file = root.resolve("a.txt").createFile()
        val commands = listOf(listOf("first"), listOf("second"))
        val actions = actions { commands }
        val asked = mutableListOf<List<List<String>>>()
        actions.launchFirst = {
            asked += it
            IOException("both failed")
        }

        actions.open(file)

        assertEquals(emptyList(), asked, "nothing runs on the calling thread")
        assertEquals(1, background.size)
        background.single().run()
        assertEquals(listOf(commands), asked)
        assertEquals(emptyList(), errors, "shown only on the FX thread")
        assertEquals(1, dispatched.size)
        dispatched.single().run()
        assertEquals(listOf("Could not open $file in Fake: both failed"), errors)
        assertEquals(emptyList(), launched)
    }

    @Test
    fun `a reveal with several commands that succeeds shows nothing`() {
        val file = root.resolve("a.txt").createFile()
        val actions = actions { listOf(listOf("first"), listOf("second")) }
        actions.launchFirst = { null }

        actions.open(file)
        background.single().run()

        assertEquals(emptyList(), dispatched)
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `a terminal opens in a folder, in the folder of a file and in the project folder for the empty area`() {
        val folder = root.resolve("dir").createDirectories()
        val file = folder.resolve("a.txt").createFile()
        val actions = actions()

        actions.openTerminal(folder)
        actions.openTerminal(file)
        project = root
        actions.openTerminal(null)

        assertEquals(listOf(folder, folder, root), terminals)
    }

    @Test
    fun `the project folder of the empty area is used as the folder of a file, too`() {
        project = root.resolve("p.txt").createFile()

        actions().openTerminal(null)

        assertEquals(listOf(root), terminals)
    }

    @Test
    fun `no terminal opens without a project folder for the empty area, or without a terminal to open it in`() {
        val folder = root.resolve("dir").createDirectories()
        val actions = actions()

        actions.openTerminal(null)
        terminalOpener = null
        actions.openTerminal(folder)

        assertTrue(terminals.isEmpty())
    }
}
