package dev.sluide.fileviewers.library

import dev.sluide.librarycode.LibraryKind
import dev.sluide.librarycode.LibraryLocation
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

    @ParameterizedTest
    @CsvSource(
        "true, 'String.java is a Java library class: read-only, a copy from jdtls that changes don''t reach.'",
        "false, 'Editing a copy of a Java library class: changes don''t reach the library.'",
    )
    fun `a Java library class is named by its file and says its changes do not reach the library`(
        locked: Boolean,
        expected: String,
    ) {
        val file = Path.of("cache", "java-classes", "69a3b620967b", "String.java")
        val location = LibraryLocation(file.parent.parent, LibraryKind.JAVA_CLASS)

        assertEquals(expected, bannerMessage(location, file, locked))
    }

    @ParameterizedTest
    @CsvSource(
        "true, 'json/__init__.py is library code (Python standard library): read-only, as changes are lost on the next install.'",
        "false, 'Editing library code (Python standard library): changes are lost on the next install.'",
    )
    fun `the Python kinds keep their banner texts`(
        locked: Boolean,
        expected: String,
    ) {
        val file = Path.of("lib", "json", "__init__.py")

        assertEquals(
            expected,
            bannerMessage(LibraryLocation(Path.of("lib"), LibraryKind.STANDARD_LIBRARY), file, locked),
        )
    }

    @Test
    fun `a file that stops being library code forgets the unlock`() {
        val policy = LibraryLockPolicy()
        policy.editAnyway()

        assertEquals(LibraryLockState.NOT_LIBRARY, policy.evaluate(false, true, false))
        assertEquals(LibraryLockState.LOCKED, policy.evaluate(true, true, false), "moved into library code again")
    }
}
