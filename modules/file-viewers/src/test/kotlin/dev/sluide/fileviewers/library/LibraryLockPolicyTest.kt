package dev.sluide.fileviewers.library

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryLockPolicyTest {
    @ParameterizedTest
    @CsvSource(
        "false, true, false, NOT_LIBRARY",
        "false, false, true, NOT_LIBRARY",
        "true, true, false, LOCKED",
        "true, false, false, SETTING_OFF",
        "true, true, true, UNLOCKED_BY_USER",
        "true, false, true, SETTING_OFF",
    )
    fun `the state of a fresh editor follows the file, the setting and the unsaved changes`(
        library: Boolean,
        setting: Boolean,
        dirty: Boolean,
        expected: String,
    ) {
        assertEquals(LibraryLockState.valueOf(expected), LibraryLockPolicy().evaluate(library, setting, dirty))
    }

    @Test
    fun `Edit Anyway shows no banner and takes no lock while the setting is off, and is not locked again after`() {
        val policy = LibraryLockPolicy()
        assertEquals(LibraryLockState.LOCKED, policy.evaluate(true, true, false))

        policy.editAnyway()
        assertEquals(LibraryLockState.UNLOCKED_BY_USER, policy.evaluate(true, true, false))
        assertEquals(LibraryLockState.SETTING_OFF, policy.evaluate(true, false, false))
        assertEquals(LibraryLockState.UNLOCKED_BY_USER, policy.evaluate(true, true, false), "not locked again")
    }

    @Test
    fun `with the setting off then on, an editor with unsaved changes stays unlocked, a saved one locks again`() {
        val policy = LibraryLockPolicy()
        assertEquals(LibraryLockState.SETTING_OFF, policy.evaluate(true, false, true))
        assertEquals(LibraryLockState.UNLOCKED_BY_USER, policy.evaluate(true, true, true))
        assertEquals(LibraryLockState.UNLOCKED_BY_USER, policy.evaluate(true, true, false), "kept after saving")

        val saved = LibraryLockPolicy()
        assertEquals(LibraryLockState.SETTING_OFF, saved.evaluate(true, false, false))
        assertEquals(LibraryLockState.LOCKED, saved.evaluate(true, true, false))
    }

    @Test
    fun `the banner names the parent folder and the file`() {
        assertEquals("json/__init__.py", displayName(Path.of("lib", "json", "__init__.py")))
        assertEquals("mod.py", displayName(Path.of("mod.py")))
    }

    @Test
    fun `a file that stops being library code forgets the unlock`() {
        val policy = LibraryLockPolicy()
        policy.editAnyway()

        assertEquals(LibraryLockState.NOT_LIBRARY, policy.evaluate(false, true, false))
        assertEquals(LibraryLockState.LOCKED, policy.evaluate(true, true, false), "moved into library code again")
    }
}
