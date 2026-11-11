package dev.sluide.encryptedfiles.ui

import dev.sluide.encryptedfiles.LockChoice
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Saving an encrypted file's tab reads the file first, since a change event may come late or never: the view's wiring
 * of [EncryptedSession.checkDisk] into Save, Lock and the idle lock (the decisions are tested in `EncryptedSessionTest`).
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EncryptedFileSavingUiTest {
    @TempDir
    lateinit var dir: Path

    private val views = EncryptedViewFixture()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(views.container, 900.0, 400.0)
        stage.show()
    }

    @AfterEach
    fun close() = views.close()

    private val other = "another long password"

    @Test
    fun `Save and Lock ask to replace a file re-keyed elsewhere although no change event came`(robot: FxRobot) {
        val file = views.create(dir.resolve("notes.txt.sluenc"), "one\n")
        val view = views.open(file, robot)
        views.unlock(view, robot)
        robot.interact { views.area(view).insertText(0, "mine ") }
        views.changePasswordElsewhere(file, other)
        val questions = mutableListOf<String>()
        view.askReplace = { question ->
            questions += question
            false
        }
        view.askUnsaved = { LockChoice.SAVE }

        assertIs<IOException>(views.fx { runCatching { view.save() }.exceptionOrNull() })
        robot.interact { view.lock() }

        assertEquals(2, questions.size)
        assertTrue(questions.all { it.startsWith("Replace notes.txt.sluenc? It was changed elsewhere") })
        assertEquals("theirs\n", views.decrypt(file, other))
        assertTrue(views.fx { view.isUnlocked && view.isDirty })
        robot.interact { view.dispose() }
    }

    enum class ChangeOnDisk { SAVED_ELSEWHERE, DELETED }

    @ParameterizedTest
    @EnumSource(ChangeOnDisk::class)
    fun `the idle lock never saves over a file changed with the same key or deleted, and stays unlocked`(
        change: ChangeOnDisk,
        robot: FxRobot,
    ) {
        val file = views.create(dir.resolve("notes.txt.sluenc"), "one\n")
        val time = FakeIdleTime()
        views.settings.idleLockMinutes.set(1)
        val view = views.open(file, robot, time)
        views.unlock(view, robot)
        robot.interact { views.area(view).insertText(0, "mine ") }
        when (change) {
            ChangeOnDisk.SAVED_ELSEWHERE -> views.saveElsewhere(file, "theirs\n")
            ChangeOnDisk.DELETED -> file.deleteExisting()
        }

        robot.interact { time.advance(Duration.ofMinutes(1)) }
        robot.interact { time.advance(Duration.ofMinutes(1)) }

        assertTrue(views.fx { view.isUnlocked && view.isDirty })
        assertEquals(IDLE_NOT_LOCKED, views.stripMessage(view))
        when (change) {
            ChangeOnDisk.SAVED_ELSEWHERE -> assertEquals("theirs\n", views.decrypt(file))
            ChangeOnDisk.DELETED -> assertFalse(file.exists())
        }
        robot.interact { view.dispose() }
    }

    enum class ConflictAnswer { RELOAD, KEEP_MINE }

    @ParameterizedTest
    @EnumSource(ConflictAnswer::class)
    fun `after the conflict banner's Reload the idle lock saves again, after Keep mine it refuses until a save`(
        answer: ConflictAnswer,
        robot: FxRobot,
    ) {
        val file = views.create(dir.resolve("notes.txt.sluenc"), "one\n")
        val time = FakeIdleTime()
        views.settings.idleLockMinutes.set(1)
        val view = views.open(file, robot, time)
        views.unlock(view, robot)
        robot.interact { views.area(view).insertText(0, "mine ") }
        views.saveElsewhere(file, "theirs\n")
        robot.interact { view.reload() }
        val button = if (answer == ConflictAnswer.RELOAD) "Reload" else "Keep mine"

        robot.interact {
            view.node
                .lookupAll(".editor-banner .button")
                .filterIsInstance<Button>()
                .single { it.text == button }
                .fire()
        }

        when (answer) {
            ConflictAnswer.RELOAD -> {
                robot.interact { views.area(view).insertText(0, "edited ") }
                robot.interact { time.advance(Duration.ofMinutes(1)) }

                assertFalse(views.fx { view.isUnlocked })
                assertEquals("edited theirs\n", views.decrypt(file))
            }

            ConflictAnswer.KEEP_MINE -> {
                robot.interact { time.advance(Duration.ofMinutes(1)) }

                assertTrue(views.fx { view.isUnlocked })
                assertEquals(IDLE_NOT_LOCKED, views.stripMessage(view))
                assertEquals("theirs\n", views.decrypt(file))
                robot.interact { view.save() }
                assertEquals(null, views.stripMessage(view))
                robot.interact { time.advance(Duration.ofMinutes(1)) }
                assertFalse(views.fx { view.isUnlocked })
                assertEquals("mine one\n", views.decrypt(file))
            }
        }
        robot.interact { view.dispose() }
    }
}
