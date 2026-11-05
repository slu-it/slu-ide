package dev.sluide.translation.settings

import dev.sluide.localmodels.ModelInstaller
import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality
import dev.sluide.translation.opusmt.OpusMtEngine
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real Translation page: its Delete buttons and its list that follows the installer need a created page; the rules
 * are in [TranslationSettingsPageModelTest]. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TranslationSettingsPageUiTest {
    @TempDir
    lateinit var dir: Path

    private val asked = CopyOnWriteArrayList<String>()
    private val engine by lazy { OpusMtEngine(dir) }
    private val installer by lazy {
        ModelInstaller(engine, executor = { it.run() }, dispatch = { Platform.runLater(it) })
    }
    private val page by lazy {
        TranslationSettingsPage(
            engine,
            installer,
            object : TranslationSettingsDialogs {
                override fun confirmDelete(question: String): Boolean {
                    asked += question
                    return true
                }
            },
        )
    }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    @AfterEach
    fun tearDown() {
        engine.close()
        installer.close()
    }

    @Test
    fun `the delete button asks and the row and the total disappear after the delete`(robot: FxRobot) {
        val medium = PairModel("de", "en", Quality.MEDIUM)
        for (file in engine.files(medium)) {
            val path = engine.folder(medium).resolve(file.path)
            Files.createDirectories(path.parent)
            RandomAccessFile(path.toFile(), "rw").use { it.setLength(file.bytes) }
        }
        var rowsBefore = 0
        var totalBefore: Pair<String, Boolean>? = null
        var emptyBefore = true
        robot.interact {
            page.node
            rowsBefore = page.deleteButtons.size
            totalBefore = page.totalShown
            emptyBefore = page.emptyShown
            page.deleteButtons.getValue(medium).fire()
        }
        var rowsAfter = -1
        var totalAfter: Pair<String, Boolean>? = null
        var emptyAfter = false
        robot.interact {
            rowsAfter = page.deleteButtons.size
            totalAfter = page.totalShown
            emptyAfter = page.emptyShown
        }
        assertEquals(1, rowsBefore)
        assertFalse(emptyBefore)
        val mb = Math.round(engine.files(medium).sumOf { it.bytes } / 1_000_000.0)
        assertEquals("Total: $mb MB" to true, totalBefore)
        assertEquals(1, asked.size)
        assertEquals(0, rowsAfter)
        assertTrue(emptyAfter)
        assertEquals(false, totalAfter?.second)
        assertFalse(engine.installed(medium))
    }
}
