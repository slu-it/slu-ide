package dev.sluide.scratches.generators

import dev.sluide.scratches.GENERATED_VALUES
import dev.sluide.scratches.GeneratorContext
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.SplittableRandom
import java.util.random.RandomGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdGeneratorsTest {
    private val now = Instant.parse("2026-10-30T12:34:56.123456700Z")

    private fun lines(
        baseName: String,
        random: RandomGenerator = SplittableRandom(7),
    ): List<String> {
        val context = GeneratorContext(random, Clock.fixed(now, ZoneOffset.UTC), ZoneOffset.UTC)
        val text = ID_GENERATORS.first { it.baseName == baseName }.generate(context)
        assertTrue(text.endsWith("\n"), baseName)
        return text.lines().dropLast(1)
    }

    private fun assertShape(
        baseName: String,
        regex: String,
    ): List<String> {
        val list = lines(baseName)
        assertEquals(GENERATED_VALUES, list.size, baseName)
        assertEquals(GENERATED_VALUES, list.toSet().size, baseName)
        list.forEach { assertTrue(it.matches(Regex(regex)), "$baseName: $it") }
        return list
    }

    private fun digit(
        alphabet: String,
        c: Char,
    ): BigInteger = BigInteger.valueOf(alphabet.indexOf(c).toLong())

    private fun assertIncreasing(list: List<String>) =
        list.zipWithNext().forEach { (a, b) -> assertTrue(a < b, "$a < $b") }

    private fun ulidValue(id: String): BigInteger {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        return id.fold(BigInteger.ZERO) { acc, c -> acc.shiftLeft(5) + digit(alphabet, c) }
    }

    private val low80 = BigInteger.ONE.shiftLeft(80) - BigInteger.ONE

    @Test
    fun `ulid is 26 crockford characters, increasing, with the clock milliseconds in front`() {
        val list = assertShape("ulid", "[0-9A-HJKMNP-TV-Z]{26}")
        assertIncreasing(list)
        list.forEach { assertEquals(now.toEpochMilli(), ulidValue(it).shiftRight(80).toLong()) }
    }

    @Test
    fun `ulid random part goes up by one`() {
        val randoms = lines("ulid").map { ulidValue(it) and low80 }
        randoms.zipWithNext().forEach { (a, b) -> assertEquals(a + BigInteger.ONE, b) }
    }

    @Test
    fun `ulid bumps the millisecond when the random part overflows`() {
        val allOnes =
            object : RandomGenerator {
                override fun nextLong(): Long = -1L

                override fun nextBytes(bytes: ByteArray) = bytes.fill(-1)
            }
        val list = lines("ulid", allOnes)
        assertIncreasing(list)
        assertEquals(GENERATED_VALUES, list.toSet().size)
        assertEquals(now.toEpochMilli(), ulidValue(list[0]).shiftRight(80).toLong())
        assertEquals(now.toEpochMilli() + 1, ulidValue(list[1]).shiftRight(80).toLong())
    }

    @Test
    fun `nanoid is 21 characters of the default alphabet`() {
        assertShape("nanoid", "[A-Za-z0-9_-]{21}")
    }

    @Test
    fun `ksuid is 27 base62 characters with the seconds since 1400000000`() {
        val list = assertShape("ksuid", "[0-9A-Za-z]{27}")
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        list.forEach { id ->
            val value = id.fold(BigInteger.ZERO) { acc, c -> acc * BigInteger.valueOf(62) + digit(alphabet, c) }
            assertEquals(now.epochSecond - 1_400_000_000L, value.shiftRight(128).toLong())
        }
    }

    @Test
    fun `objectid is 24 hex characters, increasing, with the clock seconds and one machine part`() {
        val list = assertShape("objectid", "[0-9a-f]{24}")
        assertIncreasing(list)
        list.forEach { assertEquals(now.epochSecond, it.substring(0, 8).toLong(16)) }
        assertEquals(1, list.map { it.substring(8, 18) }.toSet().size)
        list.map { it.substring(18).toInt(16) }.zipWithNext().forEach { (a, b) -> assertEquals(a + 1, b) }
    }

    @Test
    fun `snowflake is a strictly increasing decimal with the clock milliseconds and one machine id`() {
        val list = assertShape("snowflake", "[0-9]{1,19}")
        val ids = list.map { it.toLong() }
        ids.zipWithNext().forEach { (a, b) -> assertTrue(a < b) }
        ids.forEach { assertEquals(now.toEpochMilli(), (it shr 22) + 1_288_834_974_657L) }
        assertEquals(1, ids.map { (it shr 12) and 0x3ff }.toSet().size)
        assertEquals(List(GENERATED_VALUES) { it.toLong() }, ids.map { it and 0xfff })
    }
}
