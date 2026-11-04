package dev.sluide.media

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.projectdata.ProjectDataStore
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.util.Duration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.time.Duration as JavaDuration

/** Needs a scene: the view wires the stage, bar, coordinator and preferences around a (fake) playback. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MediaFileViewUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var stage: Stage
    private val writer: ExecutorService = NamedThreads.single("sluide-test")
    private val coordinator = PlaybackCoordinator()
    private val playbacks = mutableListOf<FakePlayback>()

    /** Tasks submitted to the preferences' writer. */
    private val writes = AtomicInteger()
    private val countingWriter =
        Executor { task ->
            writes.incrementAndGet()
            writer.execute(task)
        }

    /** The statuses the next fakes start with; READY when none is left. */
    private val initials = ArrayDeque<PlaybackStatus>()

    private val file: Path by lazy { Files.write(dir.resolve("song.mp3"), ByteArray(2048)) }
    private var size = 2048

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(), 600.0, 300.0)
        stage.show()
    }

    @AfterEach
    fun tearDown() {
        writer.shutdownNow()
        writer.awaitTermination(10, TimeUnit.SECONDS)
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get()

    private fun preferences() =
        MediaPreferences(
            ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()),
            JavaDuration.ofMillis(20),
            countingWriter,
        )

    private fun viewer(prefs: MediaPreferences = preferences()) =
        MediaFileViewer(
            coordinator,
            prefs,
            { FakePlayback(initials.removeFirstOrNull() ?: PlaybackStatus.READY).also(playbacks::add) },
        )

    private fun open(viewer: MediaFileViewer): MediaFileView = fx { viewer.open(file) as MediaFileView }

    /** Changes the file's size, as a rewrite on disk does. */
    private fun modify() {
        size += 100
        Files.write(file, ByteArray(size))
    }

    private fun button(
        view: MediaFileView,
        tip: String,
    ): Button =
        fx {
            view.node
                .lookupAll(".button")
                .filterIsInstance<Button>()
                .first { it.tooltip?.text?.startsWith(tip) == true }
        }

    private fun pressSpace(target: Node) =
        fx {
            Event.fireEvent(
                target,
                KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", KeyCode.SPACE, false, false, false, false),
            )
        }

    private fun calls(playback: FakePlayback) = fx { playback.calls.toList() }

    @Test
    fun `playing from the bar reaches the coordinator`() {
        val viewer = viewer()
        val first = open(viewer)
        val second = open(viewer)
        fx { button(second, "Play").fire() }
        assertEquals(PlaybackStatus.PLAYING, fx { playbacks[1].status.get() })
        fx { button(first, "Play").fire() }
        assertEquals(PlaybackStatus.PLAYING, fx { playbacks[0].status.get() })
        assertEquals(PlaybackStatus.PAUSED, fx { playbacks[1].status.get() })
        fx {
            first.dispose()
            second.dispose()
        }
    }

    @Test
    fun `a volume change reaches the preferences`() {
        val prefs = preferences()
        val view = open(viewer(prefs))
        fx { playbacks[0].volume.set(0.4) }
        assertEquals(0.4, prefs.volume)
        fx { playbacks[0].muted.set(true) }
        assertTrue(prefs.muted)
        fx { view.dispose() }
    }

    @Test
    fun `the stored volume is applied on open without saving`() {
        val prefs = preferences()
        prefs.save(0.3, true)
        // Let that write finish (the writer is single-threaded): a save on open during its delay would submit nothing.
        writer.submit {}.get()
        val before = writes.get()
        val view = open(viewer(prefs))
        assertEquals(0.3, fx { playbacks[0].volume.get() })
        assertTrue(fx { playbacks[0].muted.get() })
        assertEquals(before, writes.get(), "opening saved something")
        fx { view.dispose() }
    }

    @Test
    fun `failure hides the bar and playing marks the tab`() {
        val view = open(viewer())
        val playback = playbacks[0]
        val bar = fx { view.node.lookup(".media-controls") }
        assertEquals(ViewActivity.NONE, fx { view.activity.value })
        fx { playback.status.set(PlaybackStatus.PLAYING) }
        assertEquals(ViewActivity.RUNNING, fx { view.activity.value })
        fx { playback.status.set(PlaybackStatus.FAILED) }
        assertEquals(ViewActivity.FAILED, fx { view.activity.value })
        assertFalse(fx { bar.isVisible })
        assertFalse(fx { bar.isManaged })
        fx { view.dispose() }
    }

    @Test
    fun `reload continues at the position and play state`() {
        val view = open(viewer())
        val old = playbacks[0]
        fx {
            old.play()
            old.currentTime.set(Duration.seconds(42.0))
        }
        modify()
        fx { view.reload() }
        assertEquals(2, playbacks.size)
        assertEquals(1, fx { old.calls.count { it == "dispose" } })
        assertEquals(listOf("seek ${Duration.seconds(42.0)}", "play"), calls(playbacks[1]))
        fx { view.dispose() }
    }

    @Test
    fun `a reload of an unchanged file leaves the playback alone`() {
        val view = open(viewer())
        fx { playbacks[0].play() }
        fx { view.reload() }
        assertEquals(1, playbacks.size)
        assertEquals(listOf("play"), calls(playbacks[0]))
        fx { view.dispose() }
    }

    @Test
    fun `the resumed play pauses another playing tab`() {
        val view = open(viewer())
        val other = FakePlayback()
        fx { playbacks[0].play() }
        fx { coordinator.started(other) }
        modify()
        fx { view.reload() }
        assertEquals(listOf("pause"), calls(other))
        fx { view.dispose() }
    }

    @Test
    fun `a reload tells the coordinator the old playback is gone`() {
        val view = open(viewer())
        fx { button(view, "Play").fire() }
        modify()
        fx { view.reload() }
        // Had the old playback stayed known, the resume or this start would pause it.
        fx { coordinator.started(FakePlayback()) }
        assertFalse(calls(playbacks[0]).contains("pause"))
        fx { view.dispose() }
    }

    @Test
    fun `reload waits for the new player to be ready and does not play a paused one`() {
        val view = open(viewer())
        fx { playbacks[0].currentTime.set(Duration.seconds(5.0)) }
        initials += PlaybackStatus.LOADING
        modify()
        fx { view.reload() }
        val new = playbacks[1]
        assertEquals(emptyList(), calls(new))
        fx { new.status.set(PlaybackStatus.READY) }
        assertEquals(listOf("seek ${Duration.seconds(5.0)}"), calls(new))
        fx { view.dispose() }
    }

    @Test
    fun `a second reload while the resume is pending keeps the plan`() {
        val view = open(viewer())
        fx {
            playbacks[0].play()
            playbacks[0].currentTime.set(Duration.seconds(7.0))
        }
        initials += listOf(PlaybackStatus.LOADING, PlaybackStatus.LOADING)
        modify()
        fx { view.reload() }
        modify()
        fx { view.reload() }
        assertEquals(3, playbacks.size)
        fx { playbacks[1].status.set(PlaybackStatus.READY) }
        assertEquals(listOf("dispose"), calls(playbacks[1]), "a replaced playback resumes nothing")
        fx { playbacks[2].status.set(PlaybackStatus.READY) }
        assertEquals(listOf("seek ${Duration.seconds(7.0)}", "play"), calls(playbacks[2]))
        fx { view.dispose() }
    }

    @Test
    fun `a failure while the resume is pending does not drop the plan`() {
        val view = open(viewer())
        fx { playbacks[0].currentTime.set(Duration.seconds(3.0)) }
        initials += PlaybackStatus.LOADING
        modify()
        fx { view.reload() }
        fx { playbacks[1].status.set(PlaybackStatus.FAILED) }
        fx { playbacks[1].status.set(PlaybackStatus.READY) }
        assertEquals(listOf("seek ${Duration.seconds(3.0)}"), calls(playbacks[1]))
        fx { view.dispose() }
    }

    @Test
    fun `dispose while the resume is pending resumes nothing`() {
        val view = open(viewer())
        fx { playbacks[0].play() }
        initials += PlaybackStatus.LOADING
        modify()
        fx { view.reload() }
        fx { view.dispose() }
        fx { playbacks[1].status.set(PlaybackStatus.READY) }
        assertEquals(listOf("dispose"), calls(playbacks[1]))
    }

    @Test
    fun `the old playback after a reload changes neither the tab nor the preferences`() {
        val prefs = preferences()
        val view = open(viewer(prefs))
        modify()
        fx { view.reload() }
        fx {
            playbacks[0].status.set(PlaybackStatus.PLAYING)
            playbacks[0].volume.set(0.1)
        }
        assertEquals(ViewActivity.NONE, fx { view.activity.value })
        assertEquals(1.0, prefs.volume)
        fx { view.dispose() }
    }

    @Test
    fun `the tab's own volume carries over a reload`() {
        val prefs = preferences()
        val viewer = viewer(prefs)
        val a = open(viewer)
        val b = open(viewer)
        fx { playbacks[0].volume.set(0.2) }
        fx { playbacks[1].volume.set(0.5) }
        modify()
        fx { a.reload() }
        assertEquals(0.2, fx { playbacks[2].volume.get() })
        assertEquals(0.5, prefs.volume, "the reload saved the carried value")
        fx {
            a.dispose()
            b.dispose()
        }
    }

    @Test
    fun `dispose disposes the playback and tells the coordinator`() {
        val viewer = viewer()
        val view = open(viewer)
        val other = open(viewer)
        fx { coordinator.started(playbacks[0]) }
        fx { view.dispose() }
        fx { view.dispose() }
        assertEquals(1, fx { playbacks[0].calls.count { it == "dispose" } })
        // Forgotten: starting another no longer pauses the disposed one.
        fx { coordinator.started(playbacks[1]) }
        assertFalse(fx { playbacks[0].calls.contains("pause") })
        fx { other.dispose() }
    }

    @Test
    fun `focus, a click and Space reach the current playback, also after a reload`() {
        val view = open(viewer())
        val elsewhere = Button()
        fx { stage.scene.root = StackPane(view.node, elsewhere) }
        fx { view.focus() }
        val content = fx { view.node.lookup(".media-view") }
        assertSame(content, fx { stage.scene.focusOwner })

        // A click in the stage takes the focus back from another control.
        fx { elsewhere.requestFocus() }
        assertSame(elsewhere, fx { stage.scene.focusOwner })
        val click =
            MouseEvent(
                MouseEvent.MOUSE_PRESSED,
                1.0,
                1.0,
                1.0,
                1.0,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                null,
            )
        fx { Event.fireEvent(view.node.lookup(".media-stage"), click) }
        assertSame(content, fx { stage.scene.focusOwner })

        pressSpace(content)
        pressSpace(content)
        assertEquals(listOf("play", "pause"), calls(playbacks[0]))

        modify()
        fx { view.reload() }
        val newContent = fx { view.node.lookup(".media-view") }
        assertSame(newContent, fx { stage.scene.focusOwner })
        pressSpace(newContent)
        assertEquals(listOf("seek 0.0 ms", "play"), calls(playbacks[1]))
        assertEquals(listOf("play", "pause", "dispose"), calls(playbacks[0]))
        fx { view.dispose() }
    }
}
