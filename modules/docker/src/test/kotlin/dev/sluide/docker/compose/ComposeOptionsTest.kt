package dev.sluide.docker.compose

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposeOptionsTest {
    @Test
    fun `the defaults detach and change nothing else`() {
        val options = ComposeOptions()

        assertTrue(options.detached)
        assertEquals(emptySet(), options.profiles)
        assertEquals(listOf(false, false, false), listOf(options.build, options.forceRecreate, options.removeOrphans))
        assertNull(options.projectName)
        assertNull(options.envFiles, "null means the .env beside the file")
        assertEquals(emptyList(), options.variables)
    }

    @ParameterizedTest
    @CsvSource("FOO,true", "_x1,true", "a_B9,true", "1A,false", "A-B,false", "A B,false", "'',false", "A=B,false")
    fun `variable names follow POSIX`(
        name: String,
        valid: Boolean,
    ) = assertEquals(valid, isValidVariableName(name))

    @ParameterizedTest
    @CsvSource(
        "demo,true",
        "my-app_2,true",
        "9lives,true",
        "Demo,false",
        "-demo,false",
        "_demo,false",
        "a b,false",
        "'',false",
    )
    fun `project names follow compose's rule`(
        name: String,
        valid: Boolean,
    ) = assertEquals(valid, isValidProjectName(name))

    @ParameterizedTest
    @CsvSource("debug,true", "Tools.2,true", "a-b_c,true", "-x,false", "--help,false", "a b,false", "'',false")
    fun `profile names cannot pose as flags`(
        name: String,
        valid: Boolean,
    ) = assertEquals(valid, isValidProfileName(name))
}
