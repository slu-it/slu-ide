package dev.sluide.databases.connect

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class PasswordCacheTest {
    private val zeroed = mutableListOf<CharArray>()
    private val cache = PasswordCache().also { it.onZeroed = zeroed::add }
    private val folder = Path.of("/work/project")

    @Test
    fun `the cache stores and returns copies`() {
        val given = "secret".toCharArray()
        cache.put(folder, "a", given)
        given.fill('x')

        val first = cache.get(folder, "a")!!
        assertContentEquals("secret".toCharArray(), first)
        first.fill('y')
        assertContentEquals("secret".toCharArray(), cache.get(folder, "a"))
    }

    @Test
    fun `forget removes the password and zeroes the stored array`() {
        cache.put(folder, "a", "secret".toCharArray())

        cache.forget(folder, "a")

        assertNull(cache.get(folder, "a"))
        assertContentEquals(charArrayOf('\u0000', '\u0000', '\u0000', '\u0000', '\u0000', '\u0000'), zeroed.single())
    }

    @Test
    fun `replacing a password zeroes the old array`() {
        cache.put(folder, "a", "old".toCharArray())
        cache.put(folder, "a", "new".toCharArray())

        assertContentEquals(charArrayOf('\u0000', '\u0000', '\u0000'), zeroed.single())
        assertContentEquals("new".toCharArray(), cache.get(folder, "a"))
    }

    @Test
    fun `folders and ids are separate and paths are normalized`() {
        cache.put(folder, "a", "one".toCharArray())
        cache.put(null, "a", "two".toCharArray())

        assertContentEquals("one".toCharArray(), cache.get(Path.of("/work/other/../project"), "a"))
        assertContentEquals("two".toCharArray(), cache.get(null, "a"))
        assertNull(cache.get(folder, "b"))
        assertNull(cache.get(Path.of("/work/other"), "a"))
    }
}
