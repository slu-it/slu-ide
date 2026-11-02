package dev.sluide.databases.settings

import dev.sluide.settings.SettingsStore
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The real Databases page: its fields are bound to the page's drafts both ways, which only a created page shows; the
 * drafts' rules are in [DatabasesSettingsPageModelTest]. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class DatabasesSettingsPageUiTest {
    @TempDir
    lateinit var dir: Path

    private val settings by lazy { DatabasesSettings(SettingsStore(dir.resolve("settings.properties"))) }
    private val page by lazy { DatabasesSettingsPage(settings) }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    @Test
    fun `the fields show the settings and what is typed into them is applied`(robot: FxRobot) {
        assertEquals(listOf("Databases"), page.path)
        var shown = ""
        robot.interact {
            page.node
            page.load()
            shown = page.pageSizeField.text
        }
        assertEquals("500", shown)

        robot.interact {
            page.pageSizeField.text = "50"
            page.timeoutField.text = "30"
            page.apply()
        }
        assertEquals(50, settings.pageSize.get())
        assertEquals(30, settings.statementTimeoutSeconds.get())

        robot.interact {
            page.pageSizeField.text = "7"
            page.load()
            shown = page.pageSizeField.text
        }
        assertEquals("50", shown, "loading shows the stored value again")
    }
}
