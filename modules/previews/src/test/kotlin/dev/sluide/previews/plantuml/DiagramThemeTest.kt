package dev.sluide.previews.plantuml

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which variant a diagram theme shows. */
class DiagramThemeTest {
    @ParameterizedTest
    @CsvSource(
        "DEFAULT, false, false",
        "DEFAULT, true, true",
        "LIGHT, false, false",
        "LIGHT, true, false",
        "DARK, false, true",
        "DARK, true, true",
    )
    fun `the variant is the app's for Default and the pinned one otherwise`(
        theme: String,
        appDark: Boolean,
        expected: Boolean,
    ) {
        assertEquals(expected, DiagramTheme.valueOf(theme).dark(appDark))
    }

    @Test
    fun `the labels are the dropdown's and the page names are the script's`() {
        assertEquals(listOf("Default", "Light", "Dark"), DiagramTheme.entries.map { it.label })
        assertEquals(listOf("default", "light", "dark"), DiagramTheme.entries.map { it.page })
    }
}
