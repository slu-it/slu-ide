package dev.sluide.media

import javafx.geometry.Dimension2D
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaInfoTest {
    @ParameterizedTest
    @CsvSource(
        "0,0 B",
        "1023,1023 B",
        "1024,1.0 KB",
        "1536,1.5 KB",
        "1048575,1024.0 KB",
        "1048576,1.0 MB",
        "4404019,4.2 MB",
    )
    fun `formats the size`(
        bytes: Long,
        expected: String,
    ) {
        assertEquals("MP3 · $expected", MediaInfo.describe(Path.of("a.mp3"), bytes, null))
    }

    @Test
    fun `upper-cases the extension`() {
        assertEquals("M4A · 1 B", MediaInfo.describe(Path.of("dir/song.m4a"), 1, null))
    }

    @Test
    fun `adds the video size`() {
        assertEquals(
            "MP4 · 12.0 MB · 1920 × 1080",
            MediaInfo.describe(Path.of("v.mp4"), 12 * 1024 * 1024L, Dimension2D(1920.0, 1080.0)),
        )
    }
}
