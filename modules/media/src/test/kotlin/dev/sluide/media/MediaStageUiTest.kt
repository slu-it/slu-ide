package dev.sluide.media

import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.image.ImageView
import javafx.scene.image.WritableImage
import javafx.scene.layout.StackPane
import javafx.scene.media.MediaView
import javafx.scene.shape.Rectangle
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Needs a scene: the stage swaps its content with the player's status and tags, sizes it and styles it from CSS. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MediaStageUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(), 400.0, 400.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get()

    private fun show(
        robot: FxRobot,
        playback: FakePlayback,
        video: Boolean = false,
    ) {
        val file = Files.write(dir.resolve("song.mp3"), ByteArray(2048))
        robot.interact { stage.scene.root = StackPane(MediaStage(playback, file, video).node) }
    }

    private fun text(
        robot: FxRobot,
        cls: String,
    ): String? =
        fx {
            robot
                .lookup(".$cls")
                .queryAllAs(Label::class.java)
                .firstOrNull { it.isVisible }
                ?.text
        }

    private fun present(
        robot: FxRobot,
        cls: String,
    ): Boolean = fx { robot.lookup(".$cls").queryAll<Node>().isNotEmpty() }

    @Test
    fun `tags that arrive after the stage was built show up`(robot: FxRobot) {
        val playback = FakePlayback()
        show(robot, playback)
        assertEquals("song.mp3", text(robot, "media-stage-title"))

        robot.interact {
            playback.metadata["title"] = "Song"
            playback.metadata["artist"] = "Me"
            playback.metadata["image"] = WritableImage(10, 10)
        }

        assertEquals("Song", text(robot, "media-stage-title"))
        assertEquals("Me", text(robot, "media-stage-artist"))
        assertNull(text(robot, "media-stage-album"))
        assertTrue(present(robot, "media-stage-cover"))
        assertFalse(present(robot, "media-stage-icon"))
    }

    @Test
    fun `audio without tags shows the file name and the music note`(robot: FxRobot) {
        show(robot, FakePlayback())

        assertEquals("song.mp3", text(robot, "media-stage-title"))
        assertNull(text(robot, "media-stage-artist"))
        assertTrue(present(robot, "media-stage-icon"))
        assertFalse(present(robot, "media-stage-cover"))
    }

    @Test
    fun `failed shows the notice, takes a later error and recovers when the status changes back`(robot: FxRobot) {
        val playback = FakePlayback()
        show(robot, playback)

        robot.interact { playback.status.set(PlaybackStatus.FAILED) }
        assertEquals("Cannot play song.mp3: unknown error", text(robot, "media-stage-message"))

        robot.interact { playback.error.set("unsupported media format") }
        assertEquals("Cannot play song.mp3: unsupported media format", text(robot, "media-stage-message"))
        assertEquals("2.0 KB", text(robot, "media-stage-size"))
        assertFalse(present(robot, "media-stage-icon"))

        robot.interact { playback.status.set(PlaybackStatus.READY) }
        assertNull(text(robot, "media-stage-message"))
        assertTrue(present(robot, "media-stage-icon"))
    }

    @Test
    fun `the video follows the size of the stage`(robot: FxRobot) {
        val playback = FakePlayback(initialStatus = PlaybackStatus.LOADING, withVideoNode = true)
        show(robot, playback, video = true)
        val view =
            fx {
                robot
                    .lookup(".media-stage-video")
                    .queryAs(StackPane::class.java)
                    .children
                    .single()
            }
        assertTrue(view is MediaView)

        fun fit() = fx { view.fitWidth to view.fitHeight }
        assertEquals(400.0 to 400.0, fit())

        robot.interact { stage.width = 300.0 }
        robot.interact { stage.height = 250.0 }
        val (w, h) = fit()
        val pane = fx { robot.lookup(".media-stage-video").queryAs(StackPane::class.java) }
        assertEquals(fx { pane.width }, w)
        assertEquals(fx { pane.height }, h)
        assertTrue(w < 400.0 && h < 400.0)

        robot.interact { stage.width = 500.0 }
        assertEquals(fx { pane.width }, fit().first)
        assertTrue(fit().first > w)
    }

    @Test
    fun `the clip and the cover follow the size of the stage`(robot: FxRobot) {
        val playback = FakePlayback()
        playback.metadata["image"] = WritableImage(10, 10)
        robot.interact {
            stage.width = 500.0
            stage.height = 500.0
        }
        show(robot, playback)
        val stagePane = fx { robot.lookup(".media-stage").queryAs(StackPane::class.java) }
        val cover = fx { robot.lookup(".media-stage-cover").queryAs(ImageView::class.java) }

        fun clipSize() = fx { (stagePane.clip as Rectangle).let { it.width to it.height } }
        assertEquals(fx { stagePane.width to stagePane.height }, clipSize())
        assertEquals(240.0, fx { cover.fitHeight })

        robot.interact { stage.width = 300.0 }
        robot.interact { stage.height = 200.0 }

        assertEquals(fx { stagePane.width to stagePane.height }, clipSize())
        assertTrue(fx { stagePane.height } < 400.0)
        assertEquals(coverFit(fx { stagePane.width }, fx { stagePane.height }).second, fx { cover.fitHeight })
        assertTrue(fx { cover.fitHeight } < 240.0)
    }
}
