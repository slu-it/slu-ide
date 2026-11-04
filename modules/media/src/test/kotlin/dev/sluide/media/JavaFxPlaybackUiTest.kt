package dev.sluide.media

import javafx.scene.media.MediaException
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.fail

/** The real player: JavaFX Media's native backend needs the toolkit, so these load real files (ADR 0123). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class JavaFxPlaybackUiTest {
    @TempDir
    lateinit var dir: Path

    private val playbacks = mutableListOf<JavaFxPlayback>()

    @AfterEach
    fun tearDown(robot: FxRobot) {
        robot.interact { playbacks.forEach { it.dispose() } }
    }

    /** [read] on the FX thread, where the playback lives. */
    private fun <T> fx(read: () -> T): T =
        try {
            WaitForAsyncUtils.asyncFx(Callable { read() }).get(10, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

    private fun until(
        what: String,
        condition: () -> Boolean,
    ) {
        runCatching { WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { fx(condition) } }
            .onFailure { fail("not $what within 10 s") }
    }

    private fun garbageMp3(): Path = Files.write(dir.resolve("noise.mp3"), ByteArray(4096) { (it * 31 % 251).toByte() })

    private fun load(
        robot: FxRobot,
        file: Path,
    ): JavaFxPlayback {
        robot.interact { playbacks.add(JavaFxPlayback(file)) }
        val loaded = playbacks.last()
        until("loaded") { loaded.status.get() != PlaybackStatus.LOADING }
        return loaded
    }

    @Test
    fun `a sine WAV becomes ready with its duration`(robot: FxRobot) {
        val loaded = load(robot, writeSineWav(dir.resolve("sine.wav")))

        fx {
            assertEquals(PlaybackStatus.READY, loaded.status.get(), "error: ${loaded.error.get()}")
            assertEquals(1.0, loaded.totalDuration.get().toSeconds(), 0.05)
            assertNull(loaded.error.get())
            assertNull(loaded.videoSize.get())
        }
    }

    @Test
    fun `a seek before playing moves the current time`(robot: FxRobot) {
        val loaded = load(robot, writeSineWav(dir.resolve("sine.wav")))
        robot.interact { loaded.seek(Duration.seconds(0.5)) }
        // JavaFX moves its player's time without telling listeners while not playing.
        fx { assertEquals(0.5, loaded.currentTime.get().toSeconds(), 0.02) }
    }

    @Test
    fun `the speed reaches the player only while it plays, at every start`(robot: FxRobot) {
        // On macOS a rate given to the player while it does not play starts AVPlayer behind JavaFX's back, and
        // AVPlayer's play resets the rate to 1.
        val loaded = load(robot, writeSineWav(dir.resolve("short.wav"), seconds = 0.5))
        val player = checkNotNull(loaded.mediaPlayer)
        val set = CopyOnWriteArrayList<Pair<Double, MediaPlayer.Status>>()
        val ended = CopyOnWriteArrayList<PlaybackStatus>()
        robot.interact {
            player.rateProperty().addListener { _, _, rate -> set.add(rate.toDouble() to player.status) }
            loaded.status.addListener { _, _, status -> if (status == PlaybackStatus.ENDED) ended.add(status) }
            loaded.rate.set(1.5)
        }
        fx { assertEquals(1.0, player.rate) }

        robot.interact { loaded.play() }
        until("ended once") { ended.size == 1 }
        robot.interact { loaded.play() }
        until("ended twice") { ended.size == 2 }

        assertEquals(listOf(MediaPlayer.Status.PLAYING), set.map { it.second }.distinct(), "set while: $set")
        assertEquals(2, set.count { it.first == 1.5 }, "rates set: $set")
    }

    @Test
    fun `loops until loop is turned off and takes a speed while playing only`(robot: FxRobot) {
        // On Linux CI the null ALSA device is unclocked: each pass of the file takes almost no time.
        val loaded = load(robot, writeSineWav(dir.resolve("short.wav"), seconds = 0.5))
        val player = checkNotNull(loaded.mediaPlayer)
        val seen = CopyOnWriteArrayList<PlaybackStatus>()
        val rates = CopyOnWriteArrayList<Double>()
        robot.interact {
            loaded.status.addListener { _, _, status -> seen.add(status) }
            player.rateProperty().addListener { _, _, rate -> rates.add(rate.toDouble()) }
            loaded.loop.set(true)
            loaded.play()
        }

        until("repeated") { player.currentCount >= 2 }
        // Read in the same step: a repetition would set the speed too, but only later.
        val atOnce = fx { loaded.rate.set(2.0).let { player.rate } }
        assertEquals(2.0, atOnce)
        // macOS rewinds a repetition at rate 1: every repetition sets the speed again.
        val before = rates.size
        until("set again by a repetition") { rates.drop(before).contains(2.0) }
        fx { assertEquals(PlaybackStatus.PLAYING, loaded.status.get(), "seen: $seen") }
        robot.interact { loaded.loop.set(false) }
        until("ended") { loaded.status.get() == PlaybackStatus.ENDED }
        robot.interact { loaded.rate.set(0.75) }

        fx { assertEquals(2.0, player.rate) }
        assertEquals(listOf(PlaybackStatus.PLAYING, PlaybackStatus.ENDED), seen)
    }

    @Test
    fun `garbage named mp3 fails with a reason and leaves a loaded playback alone`(robot: FxRobot) {
        val wav = load(robot, writeSineWav(dir.resolve("sine.wav")))

        val garbage = load(robot, garbageMp3())

        fx {
            assertEquals(PlaybackStatus.FAILED, garbage.status.get())
            assertFalse(garbage.error.get().isNullOrBlank())
            // JavaFX reports the error of a media being opened to every live player; the WAV plays on.
            assertEquals(PlaybackStatus.READY, wav.status.get(), "error: ${wav.error.get()}")
            assertNull(wav.error.get())
        }
    }

    @Test
    fun `a WAV opened together with garbage still gets ready`(robot: FxRobot) {
        val sine = writeSineWav(dir.resolve("sine.wav"))
        val noise = garbageMp3()

        val loadingStatus = CopyOnWriteArrayList<PlaybackStatus>()
        robot.interact {
            val wav = JavaFxPlayback(sine)
            playbacks.add(wav)
            playbacks.add(JavaFxPlayback(noise))
            // JavaFX delivers the garbage's error to the WAV too, but here the WAV is often ready before it arrives
            // (READY is queued during the garbage's construction); this one surely arrives while the WAV loads.
            wav.reported(MediaException.Type.MEDIA_UNSUPPORTED, "another media's error")
            loadingStatus.add(wav.status.get())
        }
        val (wav, garbage) = playbacks

        until("ready") { wav.status.get() == PlaybackStatus.READY }
        assertEquals(listOf(PlaybackStatus.FAILED), loadingStatus)
        fx {
            assertNull(wav.error.get())
            assertEquals(PlaybackStatus.FAILED, garbage.status.get())
        }
    }

    @Test
    fun `plays to the end, plays again from the start and seeks after the end`(robot: FxRobot) {
        // No sound card here: the null ALSA device is unclocked, so the file plays to the end almost at once.
        val loaded = load(robot, writeSineWav(dir.resolve("short.wav"), seconds = 0.5))
        val player = checkNotNull(loaded.mediaPlayer)
        val seen = CopyOnWriteArrayList<PlaybackStatus>()
        robot.interact { loaded.status.addListener { _, _, status -> seen.add(status) } }

        robot.interact { loaded.play() }
        until("ended once") { seen.count { it == PlaybackStatus.ENDED } == 1 }
        fx { assertEquals(loaded.totalDuration.get(), loaded.currentTime.get()) }
        robot.interact { loaded.play() }
        until("ended twice") { seen.count { it == PlaybackStatus.ENDED } == 2 }
        robot.interact { loaded.seek(Duration.seconds(0.25)) }
        // The stopped player ignores seeks: the seek must reach it once it has paused.
        until("paused") { player.status == MediaPlayer.Status.PAUSED }

        fx { assertEquals(0.25, player.currentTime.toSeconds(), 0.02) }
        val expected = listOf(PlaybackStatus.PLAYING, PlaybackStatus.ENDED)
        assertEquals(expected + expected + PlaybackStatus.PAUSED, seen)
    }
}
