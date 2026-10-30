package dev.sluide.scratches.generators

import dev.sluide.scratches.GeneratorContext
import dev.sluide.scratches.GeneratorGroup
import dev.sluide.scratches.ScratchGenerator
import java.util.Base64
import java.util.HexFormat
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecretGeneratorsTest {
    private val symbols = "!#$%&*+-.:;=?@^_~"

    private fun run(
        generator: ScratchGenerator,
        seed: Long = 1,
    ): List<String> {
        val text = generator.generate(GeneratorContext(random = Random(seed)))
        assertTrue(text.endsWith("\n"))
        return text.removeSuffix("\n").split("\n")
    }

    private fun named(name: String) = SECRET_GENERATORS.single { it.name == name }

    private fun Char.isAsciiLetterOrDigit() = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

    @Test
    fun `has 27 entries in order with unique names and base names`() {
        assertEquals(27, SECRET_GENERATORS.size)
        assertEquals(27, SECRET_GENERATORS.map { it.name }.toSet().size)
        assertEquals(27, SECRET_GENERATORS.map { it.baseName }.toSet().size)
        assertTrue(SECRET_GENERATORS.all { it.group == GeneratorGroup.SECRETS })
        assertEquals(
            listOf("Password 12 (letters, digits, symbols)", "Password 12 (letters, digits)", "Password 12 (digits)"),
            SECRET_GENERATORS.take(3).map { it.name },
        )
        assertEquals(
            listOf("password-12", "password-12-alphanumeric", "password-12-digits", "password-16"),
            SECRET_GENERATORS.take(4).map { it.baseName },
        )
        assertEquals("Random 16 bytes (hex)", SECRET_GENERATORS[15].name)
        assertEquals("bytes-64-base64url", SECRET_GENERATORS.last().baseName)
    }

    @Test
    fun `every password file has 50 lines of the right length and alphabet`() {
        for (n in listOf(12, 16, 20, 32, 64)) {
            val full = run(named("Password $n (letters, digits, symbols)"))
            val alnum = run(named("Password $n (letters, digits)"))
            val digits = run(named("Password $n (digits)"))
            for (file in listOf(full, alnum, digits)) {
                assertEquals(50, file.size)
                assertTrue(file.all { it.length == n })
            }
            assertTrue(full.all { line -> line.all { it.isAsciiLetterOrDigit() || it in symbols } })
            assertTrue(alnum.all { line -> line.all { it.isAsciiLetterOrDigit() } })
            assertTrue(digits.all { line -> line.all { it in '0'..'9' } })
        }
    }

    @Test
    fun `passwords contain every required class`() {
        val full = named("Password 12 (letters, digits, symbols)")
        val alnum = named("Password 12 (letters, digits)")
        val firstChars = mutableListOf<Char>()
        for (seed in 1L..100L) {
            firstChars += run(full, seed).map { it[0] }
            for (line in run(full, seed)) {
                assertTrue(line.any { it in 'A'..'Z' }, line)
                assertTrue(line.any { it in 'a'..'z' }, line)
                assertTrue(line.any { it in '0'..'9' }, line)
                assertTrue(line.any { it in symbols }, line)
            }
            for (line in run(alnum, seed)) {
                assertTrue(line.any { it in 'A'..'Z' }, line)
                assertTrue(line.any { it in 'a'..'z' }, line)
                assertTrue(line.any { it in '0'..'9' }, line)
            }
        }
        // Without the shuffle the first character would always be the uppercase one.
        assertTrue(firstChars.any { it !in 'A'..'Z' })
    }

    @Test
    fun `the same seed gives the same output and another seed another`() {
        for (name in listOf("Password 20 (letters, digits, symbols)", "Random 32 bytes (hex)")) {
            assertEquals(run(named(name), 7), run(named(name), 7))
            assertNotEquals(run(named(name), 7), run(named(name), 8))
        }
    }

    @Test
    fun `random bytes decode to the requested size in their encoding`() {
        for (n in listOf(16, 24, 32, 64)) {
            val hex = run(named("Random $n bytes (hex)"))
            val b64 = run(named("Random $n bytes (Base64)"))
            val url = run(named("Random $n bytes (Base64URL)"))
            for (file in listOf(hex, b64, url)) {
                assertEquals(50, file.size)
                assertEquals(50, file.toSet().size)
            }
            assertTrue(hex.all { it == it.lowercase() && HexFormat.of().parseHex(it).size == n })
            assertTrue(b64.all { Base64.getDecoder().decode(it).size == n })
            assertTrue(url.all { Base64.getUrlDecoder().decode(it).size == n })
            assertTrue(url.none { line -> line.any { it == '=' || it == '+' || it == '/' } })
        }
    }

    @Test
    fun `standard Base64 keeps its padding and URL-safe drops it`() {
        // 16 bytes encode to 22 characters plus "==".
        assertTrue(run(named("Random 16 bytes (Base64)")).all { it.endsWith("==") })
        assertFalse(run(named("Random 16 bytes (Base64URL)")).any { it.endsWith("=") })
    }
}
