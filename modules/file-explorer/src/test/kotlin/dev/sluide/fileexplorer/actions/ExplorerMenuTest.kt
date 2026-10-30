package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.FileAction
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ExplorerMenuTest {
    private val file = MenuTarget(isDirectory = false, isRegularFile = true, canChange = true)
    private val folder = MenuTarget(isDirectory = true, isRegularFile = false, canChange = true)
    private val topLevel = MenuTarget(isDirectory = true, isRegularFile = false, canChange = false)
    private val busy = file.copy(canChange = false)

    private fun state(
        vararg targets: MenuTarget,
        projectOpen: Boolean = true,
        history: Boolean = true,
        canPaste: Boolean = false,
    ) = menuState(targets.toList(), projectOpen, canPaste) { history }

    @Test
    fun `a file enables Open, Rename, Delete and History but no creating`() {
        assertEquals(
            MenuState(
                open = true,
                create = false,
                rename = true,
                delete = true,
                history = true,
                fileManager = false,
                reveal = true,
                terminal = true,
                cut = true,
                copy = true,
                paste = false,
                compare = false,
            ),
            state(file),
        )
    }

    @Test
    fun `a folder enables creating, Rename and Delete but not Open or History`() {
        assertEquals(
            MenuState(
                open = false,
                create = true,
                rename = true,
                delete = true,
                history = false,
                fileManager = true,
                reveal = false,
                terminal = true,
                cut = true,
                copy = true,
                paste = false,
                compare = false,
            ),
            state(folder),
        )
    }

    @Test
    fun `a top-level folder enables creating only`() {
        assertEquals(
            MenuState(
                open = false,
                create = true,
                rename = false,
                delete = false,
                history = false,
                fileManager = true,
                reveal = false,
                terminal = true,
                cut = false,
                copy = true,
                paste = false,
                compare = false,
            ),
            state(topLevel),
        )
    }

    @Test
    fun `the empty area stands for the project folder, if one is open`() {
        assertEquals(
            MenuState(
                open = false,
                create = true,
                rename = false,
                delete = false,
                history = false,
                fileManager = true,
                reveal = false,
                terminal = true,
                cut = false,
                copy = false,
                paste = false,
                compare = false,
            ),
            state(),
        )
        assertEquals(false, state(projectOpen = false).create)
    }

    @Test
    fun `several targets enable Open and Delete only`() {
        assertEquals(
            MenuState(
                open = true,
                create = false,
                rename = false,
                delete = true,
                history = false,
                fileManager = false,
                reveal = false,
                terminal = false,
                cut = true,
                copy = true,
                paste = false,
                compare = false,
            ),
            state(file, folder),
        )
        assertEquals(
            MenuState(
                open = false,
                create = false,
                rename = false,
                delete = true,
                history = false,
                fileManager = false,
                reveal = false,
                terminal = false,
                cut = true,
                copy = true,
                paste = false,
                compare = false,
            ),
            state(folder, folder),
        )
    }

    @ParameterizedTest
    @CsvSource("2,0,true", "1,1,false", "3,0,false", "1,0,false", "0,0,false")
    fun `Compare needs exactly two regular files`(
        files: Int,
        folders: Int,
        expected: Boolean,
    ) {
        assertEquals(expected, state(*Array(files) { file }, *Array(folders) { folder }).compare)
    }

    @Test
    fun `Delete needs every target to be changeable`() {
        assertEquals(false, state(file, topLevel).delete)
        assertEquals(false, state(file, busy).delete)
        assertEquals(false, state(busy).rename)
    }

    @Test
    fun `History needs the hook to allow it`() {
        assertEquals(false, state(file, history = false).history)
    }

    @Test
    fun `Open in file manager is enabled like creating`() {
        assertEquals(true, state(folder).fileManager)
        assertEquals(true, state(topLevel).fileManager)
        assertEquals(true, state().fileManager)
        assertEquals(false, state(file).fileManager)
        assertEquals(false, state(folder, folder).fileManager)
        assertEquals(false, state(projectOpen = false).fileManager)
    }

    @Test
    fun `Reveal is for exactly one regular file`() {
        assertEquals(true, state(file).reveal)
        assertEquals(false, state(folder).reveal)
        assertEquals(false, state(file, file).reveal)
        assertEquals(false, state().reveal)
    }

    @Test
    fun `Open in Terminal is for one folder or file, or the empty area with a project`() {
        assertEquals(true, state(folder).terminal)
        assertEquals(true, state(topLevel).terminal)
        assertEquals(true, state(file).terminal)
        assertEquals(true, state().terminal)
        assertEquals(false, state(projectOpen = false).terminal)
        assertEquals(false, state(file, file).terminal)
        assertEquals(false, state(folder, file).terminal)
    }

    @Test
    fun `Copy needs a target, Cut every target to be changeable`() {
        assertEquals(true, state(topLevel).copy)
        assertEquals(true, state(file, busy).copy)
        assertEquals(false, state().copy)
        assertEquals(false, state(file, busy).cut)
        assertEquals(false, state(file, topLevel).cut)
        assertEquals(false, state().cut)
    }

    @Test
    fun `Paste is enabled when the paste would paste something`() {
        assertEquals(true, state(file, canPaste = true).paste)
        assertEquals(false, state(file).paste)
    }

    @Test
    fun `menu items show their keys without taking them`() {
        val shown = ShownKeys(KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN))

        assertEquals(KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN).displayText, shown.displayText)
        assertFalse(shown.match(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, false, true, false, false)))
        assertNotEquals<KeyCombination>(shown, ShownKeys(KeyCodeCombination(KeyCode.V, KeyCombination.CONTROL_DOWN)))
    }

    private val script = Path.of("/project/run.sh")
    private val notes = Path.of("/project/notes.txt")
    private val src = Path.of("/project/src")
    private val run = FileAction("Run", appliesTo = { it.toString().endsWith(".sh") }, perform = {})
    private val view = FileAction("View", appliesTo = { true }, perform = {})

    private fun actionsFor(vararg targets: Path) =
        fileActionsFor(targets.toList(), { it != src }, listOf(run, view)).map { it.label }

    @Test
    fun `a single regular file lists the file actions that apply to it`() {
        assertEquals(listOf("Run", "View"), actionsFor(script))
        assertEquals(listOf("View"), actionsFor(notes))
    }

    @Test
    fun `file actions are left out for several targets, a folder and the empty area`() {
        assertEquals(emptyList(), actionsFor(script, notes))
        assertEquals(emptyList(), actionsFor(src))
        assertEquals(emptyList(), actionsFor())
    }
}
