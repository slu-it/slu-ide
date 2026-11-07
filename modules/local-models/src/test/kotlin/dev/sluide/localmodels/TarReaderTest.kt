package dev.sluide.localmodels

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TarReaderTest {
    private fun read(bytes: ByteArray): List<Pair<TarEntry, String>> {
        val reader = TarReader(ByteArrayInputStream(bytes))
        return generateSequence { reader.next() }.map { it to String(reader.data.readAllBytes()) }.toList()
    }

    private fun refused(bytes: ByteArray): String = assertFailsWith<ArchiveException> { read(bytes) }.message.orEmpty()

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `regular files and nested folders come with their paths, modes and data`(gnu: Boolean) {
        val tar =
            TarBuilder(gnu)
                .dir("tool/")
                .dir("tool/lib/")
                .file("tool/lib/data.txt", "data".toByteArray())
                .file("tool/ty", ByteArray(700) { 'x'.code.toByte() }, "755".toInt(8))
                .bytes()

        val entries = read(tar)

        assertEquals(
            listOf(
                TarEntry("tool/", true, "755".toInt(8), 0),
                TarEntry("tool/lib/", true, "755".toInt(8), 0),
                TarEntry("tool/lib/data.txt", false, "644".toInt(8), 4),
                TarEntry("tool/ty", false, "755".toInt(8), 700),
            ),
            entries.map { it.first },
        )
        assertEquals("data", entries[2].second)
        assertEquals(700, entries[3].second.length, "data across a block boundary, padding skipped")
    }

    @Test
    fun `data the caller does not read is skipped`() {
        val reader =
            TarReader(
                ByteArrayInputStream(TarBuilder().file("a", ByteArray(1500)).file("b", "b".toByteArray()).bytes()),
            )
        reader.next()
        assertEquals("b", reader.next()?.path)
        assertEquals("b", String(reader.data.readAllBytes()))
        assertNull(reader.next())
    }

    @Test
    fun `a POSIX header joins its prefix and name, a GNU header ignores what is where the prefix would be`() {
        val posix = TarBuilder().entry("ty", '0', ByteArray(0), prefix = "long/folder").bytes()
        val gnu = TarBuilder(gnu = true).entry("ty", '0', ByteArray(0), filler = ByteArray(24) { 1 }).bytes()

        assertEquals("long/folder/ty", read(posix).single().first.path)
        assertEquals("ty", read(gnu).single().first.path)
    }

    @Test
    fun `a GNU long name names the entry after it`() {
        val long = "tool/" + "sub/".repeat(40) + "ty"
        val tar = TarBuilder(gnu = true).longName(long).file(long.take(99), "x".toByteArray()).bytes()

        val (entry, data) = read(tar).single()

        assertEquals(long, entry.path)
        assertEquals("x", data)
    }

    @Test
    fun `a PAX header sets the path and size of the entry after it, a global one is skipped`() {
        val path = "tööl/" + "a".repeat(150) + "/ty"
        val tar =
            TarBuilder()
                .pax("comment" to "ignored", global = true)
                .pax("path" to path, "size" to "5", "mtime" to "1.5")
                .entry("short", '0', "hello".toByteArray(), size = 0)
                .file("next", "n".toByteArray())
                .bytes()

        val entries = read(tar)

        assertEquals(listOf(path to "hello", "next" to "n"), entries.map { it.first.path to it.second })
    }

    @ParameterizedTest
    @CsvSource(
        "1, hard link",
        "2, symbolic link",
        "3, device",
        "4, device",
        "6, FIFO",
        "S, unsupported entry type",
        "K, long link name",
    )
    fun `links, devices, FIFOs and other types are refused`(
        type: Char,
        reason: String,
    ) {
        val tar = TarBuilder().file("ok", ByteArray(1)).entry("bad", type, ByteArray(0)).bytes()
        assertContains(refused(tar), reason)
    }

    @Test
    fun `a truncated archive is refused, in an entry's data, in a header and before the end marker`() {
        val whole = TarBuilder().file("a", ByteArray(2000)).unterminated()

        assertContains(refused(whole.copyOf(1200)), "truncated")
        assertContains(refused(whole + ByteArray(100)), "truncated")
        assertContains(refused(whole), "truncated")
    }

    @Test
    fun `a header with a wrong checksum is refused`() {
        val tar = TarBuilder().file("a", ByteArray(1)).bytes()
        tar[0] = 'b'.code.toByte()
        assertContains(refused(tar), "not a tar archive")
    }

    @Test
    fun `a GNU base-256 size is read`() {
        val field =
            ByteArray(12).also {
                it[0] = 0x80.toByte()
                it[11] = 5
            }
        val tar = TarBuilder(gnu = true).entry("big", '0', "hello".toByteArray(), sizeField = field).bytes()

        assertEquals("hello", read(tar).single().second)
    }

    @ParameterizedTest
    @CsvSource(
        "FF00000000000000000000FB, negative size",
        "80FFFFFFFFFFFFFFFFFFFFFF, size out of range",
        "800000007FFFFFFFFFFFFFF5, size out of range",
        "3132333435363738393A0000, not a tar archive",
    )
    fun `a size that is negative, out of range or not octal is refused`(
        hex: String,
        reason: String,
    ) {
        val field = HexFormat.of().parseHex(hex)
        val tar = TarBuilder(gnu = true).entry("big", '0', ByteArray(0), sizeField = field).bytes()
        assertContains(refused(tar), reason)
    }

    @ParameterizedTest
    @ValueSource(strings = ["-1", "12x", ""])
    fun `a PAX size that is negative or not a number is refused`(size: String) {
        val tar = TarBuilder().pax("size" to size).file("a", ByteArray(1)).bytes()
        assertContains(refused(tar), "bad size")
    }

    @Test
    fun `an extended header over 64 KB is refused`() {
        val tar = TarBuilder().pax("comment" to "x".repeat(66_000)).file("a", ByteArray(1)).bytes()
        assertContains(refused(tar), "extended header of")
    }

    @Test
    fun `a chain of extended headers is refused`() {
        val builder = TarBuilder()
        repeat(9) { builder.pax("comment" to "$it", global = true) }
        assertContains(refused(builder.file("a", ByteArray(1)).bytes()), "too many extended headers")
    }
}
