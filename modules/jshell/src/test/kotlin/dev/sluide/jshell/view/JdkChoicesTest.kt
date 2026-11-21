package dev.sluide.jshell.view

import dev.sluide.java.Jdk
import dev.sluide.java.JdkSource
import dev.sluide.jshell.JShellJdk
import dev.sluide.jshell.JShellJdks
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JdkChoicesTest {
    private val jdk25 = Jdk(Path.of("/jdks/25"), "25.0.5", 25)
    private val project = JShellJdk(jdk25, JdkSource.PROJECT_SETTING.description, null)
    private val jdk21 = Jdk(Path.of("/jdks/21"), "21.0.13.1", 21)

    // the sources are JShellJdks' own: its CHOSEN and the JdkSource descriptions of the project's chain
    @ParameterizedTest
    @MethodSource("sources")
    fun `the button names the major and the short source`(
        source: String,
        short: String,
    ) {
        assertEquals("JDK 25 · $short", JdkChoices.buttonText(JShellJdk(jdk25, source, null)))
    }

    companion object {
        @JvmStatic
        fun sources() =
            listOf(
                Arguments.of(JShellJdks.CHOSEN, "chosen"),
                Arguments.of(JdkSource.PROJECT_SETTING.description, "project setting"),
                Arguments.of(JdkSource.LOGIN_SHELL.description, "JAVA_HOME (shell)"),
                Arguments.of(JdkSource.ENVIRONMENT.description, "JAVA_HOME"),
                Arguments.of(JdkSource.THOREAU.description, "Thoreau"),
            )
    }

    @Test
    fun `the button says so when no JDK has JShell, and while it looks`() {
        assertEquals("No JDK with JShell", JdkChoices.buttonText(JShellJdk(null, "none", "why")))
        assertEquals("JDK…", JdkChoices.buttonText(null))
    }

    @Test
    fun `the tooltip has the version and home, then the problem if any`() {
        assertEquals("JDK 25.0.5 in ${jdk25.home}", JdkChoices.tooltip(JShellJdk(jdk25, "the project's JDK", null)))
        assertEquals(
            "JDK 25.0.5 in ${jdk25.home}\nThe project's JDK is gone",
            JdkChoices.tooltip(JShellJdk(jdk25, "Thoreau's own JDK", "The project's JDK is gone")),
        )
        assertEquals("why", JdkChoices.tooltip(JShellJdk(null, "none", "why")))
        assertEquals("No JDK with JShell", JdkChoices.tooltip(JShellJdk(null, "none", null)))
        assertEquals("Looking for the JDK", JdkChoices.tooltip(null))
    }

    @Test
    fun `an unknown source shows as it is`() {
        assertEquals("elsewhere", JdkChoices.shortSource("elsewhere"))
    }

    @Test
    fun `the items are the project JDK and one per installed JDK, the chosen one selected`() {
        val items = JdkChoices.items(jdk21.home, listOf(jdk25, jdk21), project)

        assertEquals(
            listOf(
                JdkChoice("Project JDK (JDK 25 · project setting)", null, false),
                JdkChoice("JDK 25.0.5 — ${jdk25.home}", jdk25.home, false),
                JdkChoice("JDK 21.0.13.1 — ${jdk21.home}", jdk21.home, true),
            ),
            items,
        )
    }

    @Test
    fun `the project JDK item says what the chain gives, or none`() {
        assertEquals(
            "Project JDK (none)",
            JdkChoices.items(null, emptyList(), JShellJdk(null, "none", "why")).first().label,
        )
    }

    @Test
    fun `without a chosen JDK the project JDK is selected`() {
        assertEquals(
            listOf(true, false),
            JdkChoices.items(null, listOf(jdk25), project).map { it.selected },
        )
    }

    @Test
    fun `a chosen JDK that is not installed selects nothing`() {
        assertEquals(
            listOf(false, false),
            JdkChoices.items(Path.of("/gone"), listOf(jdk25), project).map { it.selected },
        )
    }

    @Test
    fun `the banner is the fixed text without a JDK, the problem otherwise, none when all is well`() {
        assertEquals(
            "No JDK with JShell (JDK 9 or newer) was found.",
            JdkChoices.bannerText(JShellJdk(null, "none", "other words")),
        )
        assertEquals("gone", JdkChoices.bannerText(JShellJdk(jdk25, "Thoreau's own JDK", "gone")))
        assertNull(JdkChoices.bannerText(JShellJdk(jdk25, "the project's JDK", null)))
        assertNull(JdkChoices.bannerText(null))
    }
}
