package dev.sluide.localmodels

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveExtractorTest {
    @TempDir
    lateinit var dir: Path

    private val target by lazy { dir.resolve("target") }
    private val exe = "#!/bin/sh\necho ok\n".toByteArray()

    private fun archive(bytes: ByteArray): Path = dir.resolve("archive").also { it.writeBytes(bytes) }

    private fun extract(
        bytes: ByteArray,
        format: ArchiveFormat = ArchiveFormat.TAR_GZ,
        executable: String = "tool/ty",
        extractor: ArchiveExtractor = ArchiveExtractor(),
        cancelled: () -> Boolean = { false },
    ): Path = extractor.extract(archive(bytes), format, executable, target, cancelled)

    private fun refused(
        bytes: ByteArray,
        format: ArchiveFormat = ArchiveFormat.TAR_GZ,
        extractor: ArchiveExtractor = ArchiveExtractor(),
    ): String = assertFailsWith<ArchiveException> { extract(bytes, format, extractor = extractor) }.message.orEmpty()

    @Test
    fun `a release-style tar_gz unpacks with the executable bit from the archive and on the named executable`() {
        val tar =
            TarBuilder(gnu = true)
                .dir("./")
                .dir("tool/")
                .file("tool/ty", exe, mode = "700".toInt(8))
                .file("tool/helper", exe, mode = "744".toInt(8))
                .file("tool/README", "read me".toByteArray())
                .bytes()

        val ty = extract(gzip(tar))

        assertEquals(target.resolve("tool/ty"), ty)
        assertEquals(String(exe), ty.readText())
        assertEquals("read me", target.resolve("tool/README").readText())
        assertTrue(Files.isExecutable(ty))
        assumeTrue(posix, "POSIX modes")
        assertEquals(mode755, modeOf(ty))
        assertEquals(mode755, modeOf(target.resolve("tool/helper")), "an x bit in the archive")
        assertFalse(Files.isExecutable(target.resolve("tool/README")))
    }

    @Test
    fun `a zip unpacks and its named executable gets the executable bit that zip entries lose`() {
        val ty = extract(zip("tool/" to null, "tool/ty" to exe, "tool/README" to ByteArray(3)), ArchiveFormat.ZIP)

        assertEquals(String(exe), ty.readText())
        assertTrue(Files.isExecutable(ty))
        assumeTrue(posix, "POSIX modes")
        assertEquals(mode755, modeOf(ty))
        assertFalse(Files.isExecutable(target.resolve("tool/README")))
    }

    @Test
    fun `a plain download becomes the named executable`() {
        val ty = extract(exe, ArchiveFormat.PLAIN, executable = "ty")

        assertEquals(target.resolve("ty"), ty)
        assertEquals(String(exe), ty.readText())
        assertTrue(Files.isExecutable(ty))
    }

    @ParameterizedTest
    @CsvSource(
        "../outside/x, false",
        "ABSOLUTE/x, false",
        "tool/../../outside/x, false",
        "tool\\..\\..\\outside\\x, false",
        "../outside/x/, true",
        "ABSOLUTE/x/, true",
        "tool/../../outside/x/, true",
    )
    fun `files and folders that would leave the target are refused, in tar and in zip`(
        path: String,
        directory: Boolean,
    ) {
        val name = path.replace("ABSOLUTE", dir.resolve("outside").toString())
        for (format in listOf(ArchiveFormat.TAR_GZ, ArchiveFormat.ZIP)) {
            val bytes =
                when (format) {
                    ArchiveFormat.ZIP -> {
                        zip("tool/ty" to exe, name to if (directory) null else "evil".toByteArray())
                    }

                    // As a PAX path, which has no length limit.
                    else -> {
                        val tar = TarBuilder().file("tool/ty", exe).pax("path" to name)
                        gzip((if (directory) tar.dir("x/") else tar.file("x", ByteArray(4))).bytes())
                    }
                }

            assertContains(refused(bytes, format), "unsafe path")
            assertFalse(dir.resolve("outside/x").exists(), "$format wrote $path")
            target.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an entry that comes twice is refused`() {
        val tar = TarBuilder().file("tool/ty", exe).file("tool/./ty", "evil".toByteArray()).bytes()
        assertContains(refused(gzip(tar)), "twice")
        assertEquals(String(exe), target.resolve("tool/ty").readText(), "the first stays as it was")
    }

    @Test
    fun `a file below a file is refused`() {
        val tar = TarBuilder().file("tool", ByteArray(1)).file("tool/ty", exe).bytes()
        assertContains(refused(gzip(tar)), "clashes")
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat::class, names = ["TAR_GZ", "ZIP"])
    fun `an archive that unpacks to more than the cap is refused`(format: ArchiveFormat) {
        val small = ArchiveExtractor(maxBytes = 1000)
        val bytes =
            when (format) {
                ArchiveFormat.ZIP -> {
                    zip("tool/ty" to exe, "tool/a" to ByteArray(600), "tool/b" to ByteArray(600))
                }

                else -> {
                    gzip(
                        TarBuilder()
                            .file("tool/ty", exe)
                            .file("a", ByteArray(600))
                            .file("b", ByteArray(600))
                            .bytes(),
                    )
                }
            }

        assertContains(refused(bytes, format, small), "more than")
    }

    @Test
    fun `a tar stream that decompresses to more than the cap and the header allowance is refused`() {
        // A folder's data is skipped, not written, so only the cap of the decompressed stream sees it.
        val tar = TarBuilder().file("tool/ty", exe).entry("padded/", '5', ByteArray(5000)).bytes()
        val small = ArchiveExtractor(maxBytes = 1000, headerAllowance = 2000)

        assertContains(refused(gzip(tar), extractor = small), "more than allowed")
    }

    @Test
    fun `an entry larger than the cap is refused before it is written`() {
        val tar = TarBuilder().file("tool/ty", exe).file("big", ByteArray(5000)).bytes()
        refused(gzip(tar), extractor = ArchiveExtractor(maxBytes = 1000))
        assertFalse(target.resolve("big").exists())
    }

    @Test
    fun `an archive with more entries than the cap is refused`() {
        val tar =
            TarBuilder()
                .dir("tool/")
                .file("tool/ty", exe)
                .file("tool/a", ByteArray(1))
                .bytes()
        assertContains(refused(gzip(tar), extractor = ArchiveExtractor(maxEntries = 2)), "more than 2 entries")
    }

    @Test
    fun `an archive without the named executable is refused`() {
        val tar = TarBuilder().dir("tool/ty/").file("tool/other", exe).bytes()
        assertContains(refused(gzip(tar)), "no file tool/ty")
    }

    @Test
    fun `a truncated or damaged archive is refused`() {
        val whole = gzip(TarBuilder().file("tool/ty", ByteArray(20_000) { (it % 7).toByte() }).bytes())

        assertContains(refused(whole.copyOf(whole.size / 2)), "truncated")
        assertContains(refused(ByteArray(100) { 7 }), "not gzip-compressed")
        assertContains(refused(ByteArray(100) { 7 }, ArchiveFormat.ZIP), "not a zip archive")
    }

    @Test
    fun `unpacking stops when cancelled`() {
        val tar = gzip(TarBuilder().file("tool/ty", exe).bytes())
        assertFailsWith<CancellationException> { extract(tar, cancelled = { true }) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", ".", "./", "/ty", "../ty", "a/../ty", "a\\ty", "C:ty", "a\u0000b"])
    fun `an executable path that is not inside the archive is refused`(path: String) {
        assertContains(
            assertFailsWith<ArchiveException> {
                extract(
                    exe,
                    ArchiveFormat.PLAIN,
                    executable = path,
                )
            }.message.orEmpty(),
            "is not a path inside the archive",
        )
    }
}
