package dev.sluide.ide

import dev.sluide.settings.ProjectSettings
import dev.sluide.settings.ProjectSettingsOpeners
import javafx.beans.property.SimpleObjectProperty
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the opener registers with [ProjectSettingsOpeners] as the window's folder comes, changes and goes. */
class ProjectSettingsOpenerTest {
    private val folder = SimpleObjectProperty<Path?>()
    private val openers = ProjectSettingsOpeners()
    private val projectSettings = ProjectSettings(emptyList())
    private val opener = ProjectSettingsOpener(projectSettings, folder, openers)
    private val shown = mutableListOf<List<String>?>()
    private val a = Path.of("/work/a")
    private val b = Path.of("/work/b")

    init {
        projectSettings.show = { shown.add(it) }
    }

    @Test
    fun `a window without a folder registers nothing until it has one`() {
        opener.track()
        assertFalse(openers.show(a, null))

        folder.value = a

        assertTrue(openers.show(a, listOf("Java")))
        assertEquals(listOf<List<String>?>(listOf("Java")), shown)
    }

    @Test
    fun `a folder that is set already is registered at once`() {
        folder.value = a

        opener.track()

        assertTrue(openers.show(a, null))
    }

    @Test
    fun `a new folder replaces the old registration`() {
        folder.value = a
        opener.track()

        folder.value = b

        assertFalse(openers.show(a, null), "the old folder is no longer this window's")
        assertTrue(openers.show(b, null))
    }

    @Test
    fun `a folder that goes away takes the registration with it`() {
        folder.value = a
        opener.track()

        folder.value = null

        assertFalse(openers.show(a, null))
    }

    @Test
    fun `closing ends the registration and stops following the folder`() {
        folder.value = a
        opener.track()

        opener.close()
        folder.value = b

        assertFalse(openers.show(a, null))
        assertFalse(openers.show(b, null), "a closed opener registers no new folder")
    }
}
