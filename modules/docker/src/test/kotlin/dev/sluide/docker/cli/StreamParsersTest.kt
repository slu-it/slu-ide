package dev.sluide.docker.cli

import dev.sluide.docker.model.DockerEvent
import dev.sluide.docker.model.StatsSample
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamParsersTest {
    private fun events(name: String): List<DockerEvent> = jsonObjects(fixture(name)).mapNotNull(::parseEvent)

    private fun stats(name: String): List<StatsSample> {
        val samples = mutableListOf<StatsSample>()
        val splitter = JsonValueSplitter { value -> objectsOf(value).mapNotNullTo(samples, ::parseStats) }
        // Fed character by character, as the stream reads them.
        fixture(name).forEach(splitter::feed)
        return samples
    }

    @Test
    fun `Docker's events, every line of a real session`() {
        val lines = fixture("docker/events.jsonl").lines().filter { it.isNotBlank() }
        val events = events("docker/events.jsonl")

        assertEquals(lines.size, events.size)
        val start = events.first { it.type == "container" && it.action == "start" }
        assertEquals("e0d50c8aebff1cec6bd7e7d294d46111fccc94bba16bde24da0de25b4a8c3181", start.id)
        assertEquals("sluidefx-web-1", start.name)
        assertEquals("sluidefx", start.attributes["com.docker.compose.project"])
        assertEquals(Instant.ofEpochSecond(1793540643, 413_000_000).epochSecond, start.time!!.epochSecond)
        val volume = events.first { it.type == "volume" && it.action == "create" }
        assertEquals("sluidefx-data", volume.id)
        assertTrue(events.any { it.action == "health_status: healthy" })
        assertTrue(events.any { it.type == "network" && it.action == "connect" && it.name == "sluidefx-net" })
    }

    @Test
    fun `Podman's events, with Status as action and the name beside the id`() {
        val (start, volume, image) = events("podman/events.jsonl")

        assertEquals(
            DockerEvent(
                "container",
                "start",
                start.id,
                "shop_web_1",
                start.attributes,
                Instant.parse("2023-11-14T21:13:20.123456789Z"),
            ),
            start,
        )
        assertEquals("shop", start.attributes["com.docker.compose.project"])
        assertEquals("volume" to "create", volume.type to volume.action)
        assertEquals("shop_data", volume.id, "a volume's id is its name")
        assertEquals("shop_data", volume.name)
        assertEquals("image" to "pull", image.type to image.action)
    }

    @Test
    fun `Docker's stats stream, with the terminal escapes between its samples`() {
        val samples = stats("docker/stats-stream.txt")

        assertTrue(samples.size >= 3, "samples: ${samples.size}")
        val first = samples.first()
        assertEquals(0.01, first.cpuPercent)
        assertEquals(13_537_116L to 12_713_103_196L, first.memoryUsed to first.memoryLimit, "12.91MiB / 11.84GiB")
        assertEquals(1240L to 126L, first.networkReceived to first.networkSent)
        assertEquals(6_590_000L to 0L, first.blockRead to first.blockWritten)
        assertEquals(2, first.pids)
    }

    @Test
    fun `Podman's stats stream, a pretty-printed array per sample`() {
        val samples = stats("podman/stats-stream.txt")

        assertEquals(
            listOf(
                StatsSample(1.5, 15_200_000, 8_250_000_000, 1200, 648, 4_100_000, 0, 3),
                StatsSample(0.25, 15_300_000, 8_250_000_000, 1300, 700, 4_100_000, 0, 3),
            ),
            samples,
        )
    }

    @Test
    fun `objects without type, action or CPU value are no events or samples`() {
        assertEquals(emptyList(), jsonObjects("""{"Type":"container"}""").mapNotNull(::parseEvent))
        assertEquals(emptyList(), jsonObjects("""{"MemUsage":"1B / 2B"}""").mapNotNull(::parseStats))
    }
}
