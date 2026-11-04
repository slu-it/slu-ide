package dev.sluide.media

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaFileViewerTest {
    @TempDir
    lateinit var dir: Path

    private val viewer by lazy { MediaFileViewer(PlaybackCoordinator(), mediaPreferencesForTest(), { FakePlayback() }) }

    private fun file(name: String): Path = Files.write(dir.resolve(name), ByteArray(16))

    @ParameterizedTest
    @ValueSource(strings = ["a.mp3", "a.wav", "a.aif", "a.aiff", "a.m4a", "a.mp4", "a.m4v", "A.MP3", "b.Wav"])
    fun `claims the playable types in any case`(name: String) {
        assertTrue(viewer.canOpen(file(name)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["a.ogg", "a.flac", "a.opus", "a.aac", "a.webm", "a.mov", "a.txt", "mp3"])
    fun `leaves other types alone`(name: String) {
        assertFalse(viewer.canOpen(file(name)))
    }

    @Test
    fun `refuses a directory and a missing file`() {
        val folder = Files.createDirectory(dir.resolve("folder.mp3"))
        assertFalse(viewer.canOpen(folder))
        assertFalse(viewer.canOpen(dir.resolve("missing.mp3")))
    }

    @Test
    fun `refuses an unreadable file`() {
        val file = file("locked.mp3")
        val posix = runCatching { Files.setPosixFilePermissions(file, emptySet()) }.isSuccess
        // Running as root reads anything: only check where the permission change took effect.
        if (posix && !Files.isReadable(file)) assertFalse(viewer.canOpen(file))
    }

    @Test
    fun `video is mp4 and m4v only`() {
        assertTrue(MediaFileViewer.isVideo(Path.of("a.MP4")))
        assertTrue(MediaFileViewer.isVideo(Path.of("a.m4v")))
        assertFalse(MediaFileViewer.isVideo(Path.of("a.m4a")))
    }

    private fun mediaPreferencesForTest(): MediaPreferences =
        MediaPreferences(
            dev.sluide.projectdata.ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()),
        )
}
