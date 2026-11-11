package dev.sluide.encryptedfiles.ui

import dev.sluide.encryptedfiles.FileKey
import dev.sluide.fileviewers.ContentFileViewer
import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileContent
import dev.sluide.fileviewers.FileView
import javafx.scene.Scene
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An inner view or viewer that throws never leaves an encrypted file's tab showing its content or holding its key. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EncryptedFileFailuresUiTest {
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

    /** The plain text view, whose `wipe()` and `dispose()` throw after doing their work; counts the disposals. */
    private class FailingViewer : ContentFileViewer {
        private val real = DefaultTextFileViewer()
        var disposed = 0

        override fun opensContent(name: String) = real.opensContent(name)

        override fun open(
            file: Path,
            content: FileContent,
        ): FileView {
            val view = real.open(file, content)
            return object : FileView by view {
                override fun wipe() = throw IllegalStateException("cannot wipe")

                override fun dispose() {
                    disposed++
                    view.dispose()
                    throw IllegalStateException("cannot dispose")
                }
            }
        }
    }

    @Test
    fun `an inner view that fails to wipe or dispose still leaves the tab locked, its key zeroed`(robot: FxRobot) {
        val file = views.create(dir.resolve("notes.txt.sluenc"), "SECRET-LINE\n")
        val viewer = FailingViewer()
        val view = views.open(file, robot, viewerFor = { viewer })
        views.unlock(view, robot)
        val key = views.fx { checkNotNull(view.session.key) }

        robot.interact { view.lock() }

        assertFalse(views.fx { view.isUnlocked })
        assertTrue(key.rawDek.all { it == 0.toByte() })
        assertEquals(1, viewer.disposed)
        assertTrue(views.texts().none { "SECRET-LINE" in it })

        views.unlock(view, robot)
        val second = views.fx { checkNotNull(view.session.key) }
        robot.interact { view.dispose() }

        assertTrue(second.rawDek.all { it == 0.toByte() })
        assertEquals(2, viewer.disposed)
        assertFalse(views.fx { views.registry.anyUnlocked.get() })
    }

    @Test
    fun `a viewer that throws while opening the content locks the tab and zeroes the key`(robot: FxRobot) {
        val file = views.create(dir.resolve("notes.txt.sluenc"), "one\n")
        val viewer =
            object : ContentFileViewer {
                override fun opensContent(name: String) = true

                override fun open(
                    file: Path,
                    content: FileContent,
                ): FileView {
                    content.read()
                    throw IllegalStateException("cannot show")
                }
            }
        val view = views.open(file, robot, viewerFor = { viewer })
        val keys = mutableListOf<FileKey>()
        robot.interact {
            val render = view.session.onChange
            view.session.onChange = {
                view.session.key?.let(keys::add)
                render()
            }
        }

        views.unlock(view, robot)

        assertFalse(views.fx { view.isUnlocked })
        assertEquals("Cannot unlock the file.", views.lockedMessage(view))
        assertTrue(
            keys
                .distinct()
                .single()
                .rawDek
                .all { it == 0.toByte() },
        )
        robot.interact { view.dispose() }
    }
}
