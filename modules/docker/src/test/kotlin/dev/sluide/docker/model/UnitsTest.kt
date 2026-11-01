package dev.sluide.docker.model

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnitsTest {
    @ParameterizedTest
    @CsvSource(
        "126B, 126",
        "0B, 0",
        "1.24kB, 1240",
        "25.4MB, 25400000",
        "1.37GB, 1370000000",
        "10.84MiB, 11366564",
        "11.84GiB, 12713103196",
        "2KiB, 2048",
        "7800000, 7800000",
        "1.5 MB, 1500000",
    )
    fun `sizes in decimal and binary units are bytes`(
        text: String,
        bytes: Long,
    ) {
        assertEquals(bytes, parseSize(text))
    }

    @ParameterizedTest
    @ValueSource(strings = ["N/A", "--", "", "12XB", "MB"])
    fun `unreadable sizes are null`(text: String) {
        assertNull(parseSize(text))
    }

    @Test
    fun `pairs of sizes and percentages as docker stats prints them`() {
        assertEquals(11366564L to 12713103196L, parseSizePair("10.84MiB / 11.84GiB"))
        assertEquals(1170L to 126L, parseSizePair("1.17kB / 126B"))
        assertEquals(0L to 0L, parseSizePair("-- / --"))
        assertEquals(0L to 0L, parseSizePair(null))
        assertEquals(0.28, parsePercent("0.28%"))
        assertNull(parsePercent("--"))
    }

    @ParameterizedTest
    @CsvSource(
        "'2026-11-01 13:44:03 +0000 UTC', 2026-11-01T13:44:03Z",
        "'2026-11-01 13:08:13.735294125 +0000 UTC', 2026-11-01T13:08:13.735294125Z",
        "'2026-11-01 14:44:03 +0100 CET', 2026-11-01T13:44:03Z",
        "2026-11-01T13:44:03.42134665Z, 2026-11-01T13:44:03.421346650Z",
        "2023-11-14T22:13:20.5+01:00, 2023-11-14T21:13:20.5Z",
        "1793540643, 2026-11-01T13:44:03Z",
    )
    fun `timestamps of Docker, inspect and Podman are instants`(
        text: String,
        instant: String,
    ) {
        assertEquals(Instant.parse(instant), parseTime(text))
    }

    @ParameterizedTest
    @ValueSource(strings = ["0001-01-01T00:00:00Z", "2 minutes ago", "", "-62135596800", "0"])
    fun `zero times and texts that are no times are null`(text: String) {
        assertNull(parseTime(text))
    }

    @ParameterizedTest
    @CsvSource(
        "running, RUNNING",
        "Exited, EXITED",
        "stopped, EXITED",
        "configured, CREATED",
        "created, CREATED",
        "paused, PAUSED",
        "restarting, RESTARTING",
        "dead, DEAD",
        "removing, REMOVING",
        "whatever, UNKNOWN",
    )
    fun `Docker's and Podman's container states are one enum`(
        text: String,
        state: ContainerState,
    ) {
        assertEquals(state, ContainerState.of(text))
    }
}
