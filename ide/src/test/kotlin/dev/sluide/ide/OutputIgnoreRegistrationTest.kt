package dev.sluide.ide

import dev.sluide.java.OutputIgnoreChecks
import javafx.beans.property.SimpleObjectProperty
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the registration gives [OutputIgnoreChecks] as the window's folder comes, changes and goes. */
class OutputIgnoreRegistrationTest {
    private val folder = SimpleObjectProperty<Path?>()
    private val checks = OutputIgnoreChecks()
    private val asked = mutableListOf<List<Path>>()
    private val registration =
        OutputIgnoreRegistration(folder, checks) { folders, done ->
            asked += folders
            done(folders.take(1))
        }
    private val a = Path.of("/work/a")
    private val b = Path.of("/work/b")
    private val folders = listOf(Path.of("/work/a/target"), Path.of("/work/a/lib/target"))

    private fun answer(window: Path): List<Path>? {
        var answer: List<Path>? = listOf(Path.of("unanswered"))
        checks.check(window, folders) { answer = it }
        return answer
    }

    @Test
    fun `a window without a folder registers nothing until it has one`() {
        registration.track()
        assertEquals(null, answer(a))

        folder.value = a

        assertEquals(folders.take(1), answer(a))
        assertEquals(listOf(folders), asked)
    }

    @Test
    fun `a folder that is set already is registered at once`() {
        folder.value = a

        registration.track()

        assertEquals(folders.take(1), answer(a))
    }

    @Test
    fun `a new folder replaces the old registration`() {
        folder.value = a
        registration.track()

        folder.value = b

        assertEquals(null, answer(a), "the old folder is no longer this window's")
        assertEquals(folders.take(1), answer(b))
    }

    @Test
    fun `a folder that goes away takes the registration with it`() {
        folder.value = a
        registration.track()

        folder.value = null

        assertEquals(null, answer(a))
    }

    @Test
    fun `closing ends the registration and stops following the folder`() {
        folder.value = a
        registration.track()

        registration.close()
        folder.value = b

        assertEquals(null, answer(a))
        assertEquals(null, answer(b), "a closed registration registers no new folder")
    }
}
