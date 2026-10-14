package dev.sluide.settings

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fonts made from a [FontSpec]; loading fonts needs the JavaFX toolkit, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FontSpecUiTest {
    @Test
    fun `fonts have the chosen weight and posture`(robot: FxRobot) {
        robot.interact {
            val bold = FontSpec("Monospaced", 15.0, FontStyle.BOLD).toFont()
            assertEquals(15.0, bold.size)
            assertTrue("Bold" in bold.style && "Italic" !in bold.style, bold.style)
            val italic = FontSpec("Monospaced", 15.0, FontStyle.ITALIC).toFont()
            assertTrue("Italic" in italic.style && "Bold" !in italic.style, italic.style)
        }
    }
}
