package dev.sluide.scratches.generators

import dev.sluide.scratches.GENERATED_VALUES
import dev.sluide.scratches.GeneratorContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.SplittableRandom
import java.util.UUID
import java.util.random.RandomGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UuidGeneratorsTest {
    private val now = Instant.parse("2026-10-30T12:34:56.123456700Z")

    private fun generate(
        baseName: String,
        random: RandomGenerator = SplittableRandom(42),
    ): String {
        val context = GeneratorContext(random, Clock.fixed(now, ZoneOffset.UTC), ZoneOffset.UTC)
        return UUID_GENERATORS.first { it.baseName == baseName }.generate(context)
    }

    private fun uuids(
        baseName: String,
        random: RandomGenerator = SplittableRandom(42),
    ): List<UUID> = generate(baseName, random).lines().dropLast(1).map(UUID::fromString)

    private fun ticks(uuid: UUID): Long =
        if (uuid.version() == 1) {
            uuid.timestamp()
        } else {
            ((uuid.mostSignificantBits ushr 16) shl 12) or (uuid.mostSignificantBits and 0xfff)
        }

    private val expectedTicks =
        Duration.between(Instant.parse("1582-10-15T00:00:00Z"), now).let { it.seconds * 10_000_000 + it.nano / 100 }

    @ParameterizedTest
    @CsvSource("uuid-v1,1", "uuid-v4,4", "uuid-v6,6", "uuid-v7,7")
    fun `writes 50 lowercase uuids with its version and the RFC variant`(
        baseName: String,
        version: Int,
    ) {
        val text = generate(baseName)
        assertTrue(text.endsWith("\n"))
        val lines = text.lines().dropLast(1)
        assertEquals(GENERATED_VALUES, lines.size)
        assertEquals(GENERATED_VALUES, lines.toSet().size)
        lines.forEach {
            assertTrue(it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")), it)
            assertEquals(version, UUID.fromString(it).version(), it)
            assertEquals(2, UUID.fromString(it).variant(), it)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["uuid-v1", "uuid-v6"])
    fun `counts 100 ns ticks from the clock one by one`(baseName: String) {
        val decoded = uuids(baseName).map(::ticks)
        assertEquals(List(GENERATED_VALUES) { expectedTicks + it }, decoded)
    }

    @ParameterizedTest
    @ValueSource(strings = ["uuid-v1", "uuid-v6"])
    fun `uses a random node with the multicast bit and one clock sequence per file`(baseName: String) {
        val list = uuids(baseName)
        list.forEach { assertTrue(it.leastSignificantBits and (1L shl 40) != 0L, "multicast") }
        assertEquals(1, list.map { it.leastSignificantBits }.toSet().size)
    }

    @Test
    fun `v1 takes its clock sequence from the random source`() {
        fun clockSeq(seed: Long) =
            (uuids("uuid-v1", SplittableRandom(seed)).first().leastSignificantBits ushr 48) and 0x3fff
        assertNotEquals(clockSeq(1), clockSeq(2))
    }

    @ParameterizedTest
    @ValueSource(strings = ["uuid-v6", "uuid-v7"])
    fun `is strictly increasing as text`(baseName: String) {
        generate(baseName)
            .lines()
            .dropLast(1)
            .zipWithNext()
            .forEach { (a, b) -> assertTrue(a < b, "$a < $b") }
    }

    @Test
    fun `v7 starts with the clock milliseconds`() {
        uuids("uuid-v7").forEach { assertEquals(now.toEpochMilli(), it.mostSignificantBits ushr 16) }
    }

    @Test
    fun `v7 counter starts below 2048 and goes up by one`() {
        val counters = uuids("uuid-v7").map { it.mostSignificantBits and 0xfff }
        assertTrue(counters.first() < 2048)
        counters.zipWithNext().forEach { (a, b) -> assertEquals(a + 1, b) }
    }

    @Test
    fun `v7 bumps the millisecond when the counter overflows`() {
        // A counter start near the 12-bit limit (beyond the real 0..2047 range) forces the overflow.
        val nearTop =
            object : RandomGenerator {
                override fun nextLong(): Long = 0L

                override fun nextInt(bound: Int): Int = 4094
            }
        val lines = generate("uuid-v7", nearTop).lines().dropLast(1).map(UUID::fromString)
        val millis = lines.map { it.mostSignificantBits ushr 16 }
        assertEquals(now.toEpochMilli(), millis.first())
        assertTrue(millis.last() > millis.first())
        lines.map { it.toString() }.zipWithNext().forEach { (a, b) -> assertTrue(a < b) }
    }
}
