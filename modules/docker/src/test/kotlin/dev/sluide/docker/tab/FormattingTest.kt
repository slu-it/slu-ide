package dev.sluide.docker.tab

import dev.sluide.docker.EngineState
import dev.sluide.docker.model.EngineFlavor
import dev.sluide.docker.model.EngineInfo
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class FormattingTest {
    @ParameterizedTest
    @CsvSource("0, 0 B", "999, 999 B", "1240, 1.2 kB", "25400000, 25.4 MB", "1200000000, 1.2 GB")
    fun `sizes are shown in powers of 1000 as Docker does`(
        bytes: Long,
        text: String,
    ) {
        assertEquals(text, humanSize(bytes))
    }

    @ParameterizedTest
    @CsvSource(
        "-5, in the future",
        "30, just now",
        "60, 1 minute ago",
        "7200, 2 hours ago",
        "86400, 1 day ago",
        "7776000, 3 months ago",
        "94608000, 3 years ago",
    )
    fun `times are shown relative to now`(
        secondsAgo: Long,
        text: String,
    ) {
        val now = Instant.parse("2026-11-01T12:00:00Z")
        assertEquals(text, relativeTime(now.minusSeconds(secondsAgo), now))
    }

    @Test
    fun `absolute times are local, to the second`() {
        assertEquals(
            "2026-11-01 13:44:03",
            absoluteTime(Instant.parse("2026-11-01T12:44:03.5Z"), ZoneOffset.ofHours(1)),
        )
    }

    @Test
    fun `the header names flavor, version and the Docker context`() {
        assertEquals(
            "Docker 29.8.1 · context desktop-linux",
            engineText(EngineInfo(EngineFlavor.DOCKER, "29.8.1", context = "desktop-linux")),
        )
        assertEquals("Docker 29.8.1", engineText(EngineInfo(EngineFlavor.DOCKER, "29.8.1")))
        assertEquals("Podman 5.2.0", engineText(EngineInfo(EngineFlavor.PODMAN, "5.2.0", context = "ignored")))
    }

    @Test
    fun `the tree shows while available, a quiet note while connecting, the error with Retry otherwise`() {
        assertEquals(null, engineNote(EngineState.Available(EngineInfo(EngineFlavor.DOCKER, "29"))))
        assertEquals(EngineNote("Connecting to Docker…", retry = false), engineNote(EngineState.Idle))
        assertEquals(EngineNote("Connecting to Docker…", retry = false), engineNote(EngineState.Checking))
        assertEquals(EngineNote("Cannot connect", retry = true), engineNote(EngineState.Unavailable("Cannot connect")))
        assertEquals(EngineNote("Not found", retry = true), engineNote(EngineState.NotFound("Not found")))
    }
}
