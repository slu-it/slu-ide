package dev.sluide.fileexplorer.actions

import kotlin.test.Test
import kotlin.test.assertEquals

class ExplorerMenuTest {
    private val file = MenuTarget(isDirectory = false, isRegularFile = true, canChange = true)
    private val folder = MenuTarget(isDirectory = true, isRegularFile = false, canChange = true)
    private val topLevel = MenuTarget(isDirectory = true, isRegularFile = false, canChange = false)
    private val busy = file.copy(canChange = false)

    private fun state(
        vararg targets: MenuTarget,
        projectOpen: Boolean = true,
        history: Boolean = true,
    ) = menuState(targets.toList(), projectOpen) { history }

    @Test
    fun `a file enables Open, Rename, Delete and History but no creating`() {
        assertEquals(
            MenuState(open = true, create = false, rename = true, delete = true, history = true, fileManager = false),
            state(file),
        )
    }

    @Test
    fun `a folder enables creating, Rename and Delete but not Open or History`() {
        assertEquals(
            MenuState(open = false, create = true, rename = true, delete = true, history = false, fileManager = true),
            state(folder),
        )
    }

    @Test
    fun `a top-level folder enables creating only`() {
        assertEquals(
            MenuState(open = false, create = true, rename = false, delete = false, history = false, fileManager = true),
            state(topLevel),
        )
    }

    @Test
    fun `the empty area stands for the project folder, if one is open`() {
        assertEquals(
            MenuState(open = false, create = true, rename = false, delete = false, history = false, fileManager = true),
            state(),
        )
        assertEquals(false, state(projectOpen = false).create)
    }

    @Test
    fun `several targets enable Open and Delete only`() {
        assertEquals(
            MenuState(open = true, create = false, rename = false, delete = true, history = false, fileManager = false),
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
            ),
            state(folder, folder),
        )
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
}
