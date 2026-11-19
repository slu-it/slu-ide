package dev.sluide.testsupport

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HashCollisionsTest {
    @ParameterizedTest
    @ValueSource(strings = ["t", "up", "FROM", "BASE TABLE", "in body"])
    fun `a colliding string has the hash code of the word and is another string`(word: String) {
        val other = collidingWith(word)

        assertNotEquals(word, other)
        assertEquals(word.hashCode(), other.hashCode())
    }

    @Test
    fun `changes the last two characters by default`() {
        assertEquals("a" + ('b' - 1) + ('c' + 31), collidingWith("abc"))
    }

    @Test
    fun `a one character word gets a leading NUL`() {
        assertEquals("\u0000x", collidingWith("x"))
    }

    @Test
    fun `a first character that becomes zero is allowed`() {
        assertEquals("\u0000" + ('a' + 31), collidingWith("\u0001a"))
    }

    @Test
    fun `a second character that becomes the largest Char is allowed`() {
        assertEquals("" + ('a' - 1) + '￿', collidingWith("a￠"))
    }

    @Test
    fun `fails with a message naming the word when a shift would leave the range of a Char`() {
        for (word in listOf("￿￿", "ab￿", "\u0000a")) {
            val error = assertFailsWith<IllegalArgumentException> { collidingWith(word) }

            assertTrue(word in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `a candidate the normalization changes is skipped for a larger shift`() {
        // c2 + 31 of "FROM" is a lower-case l, which upper-casing changes; d = 2 gives a C1 control.
        assertEquals("FR" + 'M' + '\u008B', collidingWith("FROM") { it.uppercase() })
        assertEquals("" + ('a' - 2) + ('b' + 62), collidingWith("ab") { if (it[0] == 'a' - 1) "" else it })
        assertEquals("" + ('a' - 2) + ('b' + 62), collidingWith("ab") { if (it[1] == 'b' + 31) "" else it })
    }

    @Test
    fun `the fourth shift is tried and the fifth is not`() {
        val fourth = "" + ('a' - 4) + ('b' + 124)
        assertEquals(fourth, collidingWith("ab") { if (it == fourth) it else "" })

        val error = assertFailsWith<IllegalArgumentException> { collidingWith("ab") { "" } }
        assertTrue("\"ab\"" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a one character word fails when the normalization removes the NUL`() {
        assertFailsWith<IllegalArgumentException> { collidingWith("x") { it.replace("\u0000", "") } }
    }

    @ParameterizedTest
    @ValueSource(strings = ["desc", "select", "table", "azz", "zzz", "zaa", "aza"])
    fun `a letter collision is a lower-case word of letters with the same hash code`(word: String) {
        val other = collidingLettersWith(word)

        assertNotEquals(word, other)
        assertEquals(word.hashCode(), other.hashCode())
        assertEquals(other, other.lowercase())
        assertTrue(other.all { it.isLetter() })
        assertEquals(word.dropLast(3), other.dropLast(3))
    }

    @ParameterizedTest
    @ValueSource(strings = ["ab", "ABC", "ab1", "ab{", "`bc"])
    fun `a letter collision refuses what is not three or more letters a to z`(word: String) {
        val error = assertFailsWith<IllegalArgumentException> { collidingLettersWith(word) }

        assertTrue(word in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a letter collision fails when the only word with that hash code is the word itself`() {
        // checked by the search in the test below: "aaa" has no other triple of lower-case Latin letters
        val error = assertFailsWith<IllegalStateException> { collidingLettersWith("aaa") }

        assertTrue("aaa" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `every triple of letters has a letter collision except 102 that have none`() {
        val letters = 'a'..'z'
        val none = mutableListOf<String>()
        for (x in letters) {
            for (y in letters) {
                for (z in letters) {
                    val word = "$x$y$z"
                    val other =
                        try {
                            collidingLettersWith(word)
                        } catch (e: IllegalStateException) {
                            assertTrue(e.message.orEmpty().startsWith("no letter collision for"), e.message)
                            none += word
                            continue
                        }

                    assertNotEquals(word, other)
                    assertEquals(word.hashCode(), other.hashCode(), word)
                    assertTrue(other.all { Character.isLowerCase(it) && Character.isLetter(it) }, word)
                }
            }
        }

        assertEquals(102, none.size, none.toString())
        assertTrue("aaa" in none)
    }
}
