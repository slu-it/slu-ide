package dev.sluide.codecs.conversion

import dev.sluide.codecs.codec.BytePipeline
import dev.sluide.codecs.codec.CodecId
import dev.sluide.codecs.codec.Compression
import dev.sluide.codecs.detect.FileKind
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class ConversionModelTest {
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val model = ConversionModel(Clock.fixed(now, ZoneOffset.UTC))
    private val pngMagic = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val sampleJwt =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ." +
            "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    private fun use(codec: CodecId) = model.updateSettings { it.copy(codec = codec) }

    private fun textOf(model: ConversionModel) = (model.text as TextContent.Text).text

    private fun base64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun token(claims: String) =
        Base64.getUrlEncoder().withoutPadding().let { b ->
            "${b.encodeToString("{\"alg\":\"HS256\"}".toByteArray())}.${b.encodeToString(claims.toByteArray())}.abcd"
        }

    @Test
    fun `typing on the text side encodes`() {
        model.editText("hello world")
        assertEquals("aGVsbG8gd29ybGQ=", model.encoded)
        assertEquals(Side.TEXT, model.source)
        assertEquals(Status.Info("11 chars · 11 bytes"), model.textStatus)
        assertEquals(Status.Info("16 chars"), model.encodedStatus)
    }

    @Test
    fun `typing on the encoded side decodes`() {
        model.editEncoded("aGVsbG8gd29ybGQ=")
        assertEquals("hello world", textOf(model))
        assertEquals(Side.ENCODED, model.source)
        assertEquals(Status.Info("Valid Base64"), model.encodedStatus)
        assertEquals(Status.Info("11 chars · 11 bytes"), model.textStatus)
    }

    @Test
    fun `an error on the source side keeps the other side`() {
        model.editText("abc")
        model.editEncoded("!!")
        assertEquals("abc", textOf(model))
        assertEquals(Status.Error("Invalid Base64 character '!'", 0), model.encodedStatus)
        assertEquals(Status.Info("3 chars · 3 bytes"), model.textStatus)
        model.editEncoded("YWJj")
        assertEquals(Status.Info("Valid Base64"), model.encodedStatus)
    }

    @Test
    fun `an encoding error keeps the encoded side and sets the text status`() {
        model.editText("abc")
        model.updateSettings { it.copy(charset = Charsets.US_ASCII) }
        model.editText("abü")
        assertEquals("YWJj", model.encoded)
        assertEquals(Status.Error("'ü' cannot be written in US-ASCII", 2), model.textStatus)
        assertNull(model.bytesToSave())
    }

    @Test
    fun `a codec switch keeps the source and recomputes the other side`() {
        model.editText("hi")
        use(CodecId.HEX)
        assertEquals(Side.TEXT, model.source)
        assertEquals("6869", model.encoded)
        model.editEncoded("6869")
        use(CodecId.BASE32)
        assertEquals(Side.ENCODED, model.source)
        assertEquals("6869", model.encoded)
        assertEquals("hi", textOf(model))
        assertTrue(model.encodedStatus is Status.Error)
    }

    @Test
    fun `an option change keeps the encoded source`() {
        model.editEncoded("YQ")
        assertEquals("a", textOf(model))
        model.updateSettings { it.copy(charset = Charsets.UTF_16BE) }
        assertEquals(Side.ENCODED, model.source)
        assertEquals("YQ", model.encoded)
    }

    @Test
    fun `charset change re-encodes the text`() {
        model.editText("ü")
        assertEquals("w7w=", model.encoded)
        model.updateSettings { it.copy(charset = Charsets.ISO_8859_1) }
        assertEquals("/A==", model.encoded)
        assertEquals(Status.Info("1 char · 1 byte"), model.textStatus)
    }

    @Test
    fun `hash forces the text source, fills the rows and a key makes them HMACs`() {
        model.editEncoded("aGVsbG8=")
        use(CodecId.HASH)
        assertEquals(Side.TEXT, model.source)
        assertEquals("hello", textOf(model))
        assertEquals(7, model.hashes.size)
        assertEquals("5d41402abc4b2a76b9719d911017c592", model.hashes.first { it.algorithm.title == "MD5" }.value)
        assertTrue(
            model.encoded
                .lines()
                .first()
                .startsWith("MD5  5d41402a"),
        )
        assertEquals(Status.Info("7 hashes"), model.encodedStatus)
        model.updateSettings { it.copy(hash = it.hash.copy(key = "k")) }
        assertEquals(6, model.hashes.size)
        assertEquals(Status.Info("HMAC with key"), model.encodedStatus)
        use(CodecId.BASE64)
        assertTrue(model.hashes.isEmpty())
        assertEquals("aGVsbG8=", model.encoded)
    }

    @Test
    fun `editEncoded is ignored for hash`() {
        model.editText("x")
        use(CodecId.HASH)
        var calls = 0
        model.onChange = { calls++ }
        val before = model.encoded
        model.editEncoded("anything", pasted = true)
        assertEquals(before, model.encoded)
        assertEquals("x", textOf(model))
        assertEquals(0, calls)
    }

    @Test
    fun `jwt forces the encoded source, shows the token on the text side and ignores editText`() {
        model.editText("hi")
        use(CodecId.JWT)
        assertEquals(Side.ENCODED, model.source)
        assertTrue(model.encodedStatus is Status.Error)
        model.editEncoded(sampleJwt)
        assertNotNull(model.jwt)
        assertTrue(textOf(model).startsWith("Header\n"))
        assertEquals(Status.Info("Valid JWT"), model.encodedStatus)
        var calls = 0
        model.onChange = { calls++ }
        model.editText("changed")
        assertEquals(0, calls)
        assertTrue(textOf(model).startsWith("Header\n"))
        use(CodecId.BASE64)
        assertNull(model.jwt)
    }

    @Test
    fun `an expired token says how long ago`() {
        use(CodecId.JWT)
        model.editEncoded(token("{\"exp\":${now.minusSeconds(3 * 3600).epochSecond}}"))
        assertEquals(Status.Info("Valid JWT · expired 3 h ago"), model.encodedStatus)
        assertTrue(model.jwt?.expired == true)
    }

    @Test
    fun `binary data decodes to bytes with the file kind`() {
        model.editEncoded(base64(pngMagic))
        val content = assertInstanceOf(TextContent.Bytes::class.java, model.text)
        assertEquals(FileKind.PNG, content.kind)
        assertArrayEquals(pngMagic, content.bytes)
        assertEquals(Status.Info("Binary data · 8 bytes · PNG image"), model.textStatus)
    }

    @Test
    fun `a gzip result gets a hint that switches the compression`() {
        val gz = BytePipeline.compress("hello".toByteArray(), Compression.GZIP)
        model.editEncoded(base64(gz))
        assertEquals(FileKind.GZIP, (model.text as TextContent.Bytes).kind)
        assertEquals("Looks gzip-compressed", model.hint?.message)
        assertEquals("Gunzip", model.hint?.action)
        model.applyHint()
        assertEquals(Compression.GZIP, model.settings.compression)
        assertEquals("hello", textOf(model))
        assertNull(model.hint)
    }

    @Test
    fun `a zlib result gets the Inflate hint`() {
        model.editEncoded(base64(BytePipeline.compress("hello".toByteArray(), Compression.ZLIB)))
        assertEquals("Inflate", model.hint?.action)
    }

    @Test
    fun `compression applies when encoding`() {
        model.updateSettings { it.copy(compression = Compression.GZIP) }
        model.editText("hello")
        assertTrue(model.encoded.startsWith("H4sI"))
    }

    @Test
    fun `a pasted strong detection suggests the switch even when the text decodes`() {
        model.editEncoded(sampleJwt, pasted = true)
        assertEquals("Looks like a JWT", model.hint?.message)
        assertEquals("Switch", model.hint?.action)
        model.applyHint()
        assertEquals(CodecId.JWT, model.settings.codec)
        assertNull(model.hint)
        assertNotNull(model.jwt)
    }

    @Test
    fun `a weak detection needs a failing decode, and typing never detects`() {
        model.editEncoded("48656c6c6f20776f", pasted = true)
        assertNull(model.hint)
        model.editEncoded("a%20b%26c", pasted = true)
        assertEquals("Looks like URL-encoded text", model.hint?.message)
        model.editEncoded("a%20b%26c")
        assertNull(model.hint)
    }

    @Test
    fun `an edit or a settings change clears the detection hint`() {
        model.editEncoded("a%20b%26c", pasted = true)
        model.editText("x")
        assertNull(model.hint)
        model.editEncoded("a%20b%26c", pasted = true)
        model.updateSettings { it.copy(lineByLine = true) }
        assertNull(model.hint)
        model.editEncoded("a%20b%26c", pasted = true)
        model.open("plain prose")
        assertNull(model.hint)
    }

    @Test
    fun `open switches the codec for a detected text`() {
        model.open(sampleJwt)
        assertEquals(CodecId.JWT, model.settings.codec)
        assertEquals(Side.ENCODED, model.source)
        assertNotNull(model.jwt)
        assertNull(model.hint)
    }

    @Test
    fun `open puts prose on the text side and leaves a JWT codec`() {
        model.open("just some prose")
        assertEquals(Side.TEXT, model.source)
        assertEquals(CodecId.BASE64, model.settings.codec)
        assertEquals("anVzdCBzb21lIHByb3Nl", model.encoded)
        use(CodecId.JWT)
        model.open("more prose")
        assertEquals(CodecId.BASE64, model.settings.codec)
        assertEquals("more prose", textOf(model))
    }

    @Test
    fun `openFile reads text`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("a.txt")
        Files.writeString(file, "héllo")
        assertNull(model.openFile(file))
        assertEquals("héllo", textOf(model))
        assertEquals(Side.TEXT, model.source)
        assertEquals(base64("héllo".toByteArray()), model.encoded)
    }

    @Test
    fun `openFile shows binary data as bytes and a data URI takes the kind as MIME`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("logo.bin")
        Files.write(file, pngMagic + byteArrayOf(0, 1))
        model.updateSettings { it.copy(base64 = it.base64.copy(dataUri = true)) }
        assertNull(model.openFile(file))
        val content = model.text as TextContent.Bytes
        assertEquals(FileKind.PNG, content.kind)
        assertEquals("logo.bin", content.name)
        assertTrue(model.encoded.startsWith("data:image/png;base64,"))
    }

    @Test
    fun `a data URI for text names the charset, for a file name its type, else octet-stream`(
        @TempDir dir: Path,
    ) {
        model.updateSettings { it.copy(base64 = it.base64.copy(dataUri = true)) }
        model.editText("hi")
        assertEquals("data:text/plain;charset=UTF-8;base64,aGk=", model.encoded)
        val json = dir.resolve("a.json")
        Files.writeString(json, "{}")
        model.openFile(json)
        assertTrue(model.encoded.startsWith("data:application/json;base64,"), model.encoded)
        val bin = dir.resolve("a.xyz")
        Files.write(bin, byteArrayOf(0, 1, 2))
        model.openFile(bin)
        assertTrue(model.encoded.startsWith("data:application/octet-stream;base64,"), model.encoded)
    }

    @Test
    fun `a file over 1 MiB is refused and the state stays`(
        @TempDir dir: Path,
    ) {
        model.editText("keep")
        val file = dir.resolve("big.bin")
        Files.write(file, ByteArray(1024 * 1024 + 1))
        var calls = 0
        model.onChange = { calls++ }
        assertEquals("big.bin is larger than 1 MiB", model.openFile(file))
        assertEquals("keep", textOf(model))
        assertEquals(0, calls)
    }

    @Test
    fun `a missing file gives the error message`(
        @TempDir dir: Path,
    ) {
        var calls = 0
        model.editText("keep")
        model.onChange = { calls++ }
        assertEquals("Cannot read missing.txt: file not found", model.openFile(dir.resolve("missing.txt")))
        assertEquals("keep", textOf(model))
        assertEquals(0, calls)
    }

    @Test
    fun `a file that cannot be read gives the reason and keeps the state`(
        @TempDir dir: Path,
    ) {
        model.editText("keep")
        var calls = 0
        model.onChange = { calls++ }
        val message = model.openFile(dir)
        assertNotNull(message)
        assertTrue(message!!.startsWith("Cannot read ${dir.fileName}: "), message)
        assertEquals("keep", textOf(model))
        assertEquals(0, calls)
    }

    @Test
    fun `a file without permission says access denied`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("secret.txt")
        Files.writeString(file, "x")
        file.toFile().setReadable(false)
        assumeFalse(Files.isReadable(file), "the user can read anything (root)")
        model.editText("keep")
        var calls = 0
        model.onChange = { calls++ }
        assertEquals("Cannot read secret.txt: access denied", model.openFile(file))
        assertEquals("keep", textOf(model))
        assertEquals(0, calls)
    }

    @Test
    fun `a text codec cannot escape binary data`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("x.bin")
        Files.write(file, byteArrayOf(0, 1, 2))
        model.openFile(file)
        val before = model.encoded
        use(CodecId.JSON)
        assertEquals(Status.Error("Binary data cannot be escaped; pick a byte codec or Hash", null), model.textStatus)
        assertEquals(before, model.encoded)
        use(CodecId.HASH)
        assertEquals(7, model.hashes.size)
    }

    @Test
    fun `editBytesAsText turns the dump into editable text`() {
        model.editEncoded(base64(pngMagic))
        assertArrayEquals(pngMagic, model.bytesToSave())
        model.editBytesAsText()
        assertTrue(textOf(model).startsWith("�"))
        assertEquals(Side.TEXT, model.source)
    }

    @Test
    fun `bytesToSave encodes the text in the charset`() {
        model.editText("ü")
        assertArrayEquals("ü".toByteArray(), model.bytesToSave())
        model.updateSettings { it.copy(charset = Charsets.ISO_8859_1) }
        assertArrayEquals(byteArrayOf(-4), model.bytesToSave())
    }

    @Test
    fun `clear empties both sides and makes the cleared side the source`() {
        model.editText("hello")
        model.clear(Side.ENCODED)
        assertEquals(Side.ENCODED, model.source)
        assertEquals("", textOf(model))
        assertEquals("", model.encoded)
        model.editText("hello")
        model.clear(Side.TEXT)
        assertEquals(Side.TEXT, model.source)
        assertEquals("", model.encoded)
    }

    @Test
    fun `line by line converts every line and names the failing ones`() {
        model.updateSettings { it.copy(lineByLine = true) }
        model.editText("a\nb\n")
        assertEquals("YQ==\nYg==\n", model.encoded)
        model.editEncoded("YQ==\n!!\nYg==\n$$")
        assertEquals(
            Status.Error("Lines 2, 4: Invalid Base64 character '!'", 5),
            model.encodedStatus,
        )
        assertEquals("a\nb\n", textOf(model))
        model.editEncoded("YQ==\nYg==")
        assertEquals("a\nb", textOf(model))
    }

    @Test
    fun `a binary line is an error in line mode`() {
        model.updateSettings { it.copy(lineByLine = true) }
        model.editEncoded(base64(pngMagic))
        assertEquals(Status.Error("Line 1: Line is binary data", null), model.encodedStatus)
    }

    @Test
    fun `an escape codec encodes the text and a bad escape keeps it`() {
        use(CodecId.JSON)
        model.editText("a\"b\n")
        assertEquals("a\\\"b\\n", model.encoded)
        model.editEncoded("x\\qy")
        assertTrue(model.encodedStatus is Status.Error)
        assertEquals("a\"b\n", textOf(model))
    }

    @Test
    fun `a URL error carries its position through to the encoded status`() {
        use(CodecId.URL)
        model.editEncoded("100%zz")
        assertEquals(Status.Error("Invalid percent escape", 3), model.encodedStatus)
    }

    @Test
    fun `onChange is called once per action`() {
        var calls = 0
        model.onChange = { calls++ }
        val actions: List<() -> Unit> =
            listOf(
                { model.editText("a") },
                { model.editEncoded("YQ==", pasted = true) },
                { model.editEncoded("!!", pasted = true) },
                { use(CodecId.HEX) },
                { model.open(sampleJwt) },
                { model.clear(Side.TEXT) },
                { model.open("some prose") },
            )
        for (action in actions) {
            val before = calls
            action()
            assertEquals(before + 1, calls)
        }
    }

    @Test
    fun `decoding with a bad gzip stream passes the error through`() {
        model.updateSettings { it.copy(compression = Compression.GZIP) }
        model.editEncoded("aGVsbG8=")
        assertEquals(Status.Error("Not gzip data", null), model.encodedStatus)
    }

    @Test
    fun `a text that happens to look like a zlib stream is text and gets no hint`() {
        model.editEncoded("eF4yICsgeV4y")
        assertEquals("x^2 + y^2", textOf(model))
        assertNull(model.hint)
    }

    @Test
    fun `a data URI takes its MIME type from the compression, and the file kind only for binary data`() {
        model.updateSettings { it.copy(base64 = it.base64.copy(dataUri = true)) }
        model.editText("%PDF-1.4")
        assertTrue(model.encoded.startsWith("data:text/plain;charset=UTF-8;base64,"), model.encoded)
        val mimes =
            mapOf(
                Compression.GZIP to "application/gzip",
                Compression.ZLIB to "application/zlib",
                Compression.DEFLATE to "application/octet-stream",
            )
        for ((compression, mime) in mimes) {
            model.updateSettings { it.copy(compression = compression) }
            assertTrue(model.encoded.startsWith("data:$mime;base64,"), "$compression: ${model.encoded}")
        }
    }

    @Test
    fun `a strong detection suggests a switch although the current codec decodes the text`() {
        use(CodecId.URL)
        model.editEncoded("<~87cURD]i,\"Ebo80~>", pasted = true)
        assertEquals(Status.Info("Valid URL"), model.encodedStatus)
        assertEquals("Looks like Ascii85", model.hint?.message)
        model.applyHint()
        assertEquals(CodecId.ASCII85, model.settings.codec)
        assertEquals("Hello World!", textOf(model))
    }

    @Test
    fun `a detection hint wins over a compression hint`() {
        val detection = Hint("Looks like a JWT", "Switch") { it }
        val compression = Hint("Looks gzip-compressed", "Gunzip") { it }
        assertEquals(detection, chooseHint(detection, compression))
        assertEquals(compression, chooseHint(null, compression))
        assertEquals(detection, chooseHint(detection, null))
        assertNull(chooseHint(null, null))
    }

    @Test
    fun `bytes with a known binary format are binary even when the charset accepts them`() {
        model.updateSettings { it.copy(charset = Charsets.ISO_8859_1) }
        model.editEncoded(base64(pngMagic))
        assertEquals(FileKind.PNG, (model.text as TextContent.Bytes).kind)
    }

    @Test
    fun `open uses the text inside one pair of quotes when that is detected`() {
        model.open("\"$sampleJwt\"")
        assertEquals(CodecId.JWT, model.settings.codec)
        assertEquals(sampleJwt, model.encoded)
        use(CodecId.BASE64)
        model.open("'aGVsbG8gd29ybGQ='")
        assertEquals("aGVsbG8gd29ybGQ=", model.encoded)
        assertEquals("hello world", textOf(model))
    }

    @Test
    fun `open keeps the quotes of a text that is not detected inside`() {
        model.open("'just some prose'")
        assertEquals("'just some prose'", textOf(model))
        assertEquals(Side.TEXT, model.source)
    }

    @Test
    fun `characters are counted as code points and empty input has an empty status`() {
        model.editText("😀")
        assertEquals(Status.Info("1 char · 4 bytes"), model.textStatus)
        use(CodecId.JSON)
        assertEquals(Status.Info("1 char"), model.encodedStatus)
        model.editText("")
        assertEquals(Status.Info(""), model.textStatus)
        assertEquals(Status.Info(""), model.encodedStatus)
        model.editEncoded("")
        assertEquals(Status.Info(""), model.textStatus)
        assertEquals(Status.Info(""), model.encodedStatus)
    }
}
