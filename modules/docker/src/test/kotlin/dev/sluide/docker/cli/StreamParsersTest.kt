package dev.sluide.docker.cli

import dev.sluide.docker.model.DockerEvent
import dev.sluide.docker.model.StatsSample
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    private fun event(json: String): DockerEvent? = parseEvent(dockerJson.readTree(json))

    @Test
    fun `an event needs a type and an action, Podman's status is the action`() {
        assertNull(event("""{"Action":"start"}"""))
        assertNull(event("""{"Type":"container"}"""))
        assertNull(event("""{"Type":"container","Status":" "}"""))
        assertEquals("pull", event("""{"Type":"image","Status":"pull"}""")?.action)
        assertEquals("start", event("""{"Type":"container","Action":"start","Status":"x"}""")?.action)
    }

    @Test
    fun `an event's id comes from the actor, the event, the name, in that order`() {
        val chain = """"Attributes":{"name":"web"}"""
        assertEquals("a1", event("""{"Type":"t","Action":"a","ID":"top","Actor":{"ID":"a1",$chain}}""")?.id)
        assertEquals("top", event("""{"Type":"t","Action":"a","ID":"top","Actor":{$chain}}""")?.id)
        assertEquals("top", event("""{"Type":"t","Action":"a","id":"top"}""")?.id)
        assertEquals("web", event("""{"Type":"t","Action":"a","Actor":{$chain},"Name":"other"}""")?.id)
        assertEquals("other", event("""{"Type":"t","Action":"a","Name":"other"}""")?.id)
        assertEquals("", event("""{"Type":"t","Action":"a"}""")?.id)
    }

    @Test
    fun `an event's name is the name attribute or the Name field, none without both`() {
        assertEquals(
            "web",
            event("""{"Type":"t","Action":"a","Actor":{"Attributes":{"name":"web"}},"Name":"x"}""")?.name,
        )
        assertEquals("podman", event("""{"Type":"t","Action":"a","Attributes":{"name":"podman"}}""")?.name)
        assertEquals("x", event("""{"Type":"t","Action":"a","Name":"x"}""")?.name)
        assertNull(event("""{"Type":"t","Action":"a"}""")?.name)
    }

    @Test
    fun `an event's time is the nanoseconds, else the seconds, else a text`() {
        val head = """{"Type":"t","Action":"a","""
        assertEquals(
            Instant.ofEpochSecond(1793540643, 374_888_358),
            event(head + """"time":5,"timeNano":1793540643374888358}""")?.time,
        )
        assertEquals(Instant.ofEpochSecond(1700000000), event(head + """"time":1700000000}""")?.time)
        assertEquals(Instant.parse("2023-11-14T21:13:20Z"), event(head + """"Time":"2023-11-14T21:13:20Z"}""")?.time)
        assertNull(event(head + """"Time":"yesterday"}""")?.time)
        assertNull(event(head + """"time":1e30}""")?.time, "outside the range of a long")
        assertNull(event(head + """"Time":{"a":1}}""")?.time)
        assertNull(event(head + """"x":1}""")?.time)
    }

    @Test
    fun `a stats sample keeps every figure apart, the pids missing as null`() {
        val node =
            dockerJson.readTree(
                """{"CPUPerc":"1.5%","MemUsage":"2MiB / 4MiB","NetIO":"3kB / 5kB","BlockIO":"7MB / 9MB","PIDs":"11"}""",
            )

        assertEquals(
            StatsSample(1.5, 2_097_152, 4_194_304, 3000, 5000, 7_000_000, 9_000_000, 11),
            parseStats(node),
        )
        assertNull(parseStats(dockerJson.readTree("""{"CPUPerc":"0%"}"""))?.pids)
        assertNull(parseStats(dockerJson.readTree("""{"cpu_percent":"--"}""")))
    }
}
