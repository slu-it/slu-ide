package dev.sluide.encryptedfiles.ui

import dev.sluide.encryptedfiles.EncryptedFiles
import dev.sluide.encryptedfiles.FileKey
import dev.sluide.encryptedfiles.KdfParams
import dev.sluide.encryptedfiles.Sluenc
import dev.sluide.encryptedfiles.settings.EncryptedFilesSettings
import dev.sluide.fileviewers.FileViewers
import dev.sluide.settings.SettingsStore
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.ObjectProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the viewer decides without a scene; the view it makes is in `EncryptedFileViewUiTest`. */
class EncryptedFileViewerTest {
    @TempDir
    lateinit var dir: Path

    private val files = EncryptedFiles(Duration.ofMinutes(1)) { _, _ -> }
    private val settings by lazy { EncryptedFilesSettings(SettingsStore(dir.resolve("settings.properties"))) }

    private class Failing : ObjectProvider<FileViewers> {
        override fun getObject(vararg args: Any?): FileViewers = throw IllegalStateException("no viewers")

        override fun getObject(): FileViewers = throw IllegalStateException("no viewers")

        override fun getIfAvailable(): FileViewers? = null

        override fun getIfUnique(): FileViewers? = null
    }

    private fun viewer() = EncryptedFileViewer(files, settings, Failing())

    private fun FileKey.zeroed() = rawDek.all { it == 0.toByte() }

    @Test
    fun `it opens files named sluenc`() {
        assertEquals(setOf("sluenc"), viewer().fileExtensions)
    }

    @ParameterizedTest
    @CsvSource(
        "notes.md.sluenc, true",
        "NOTES.SLUENC, true",
        "notes.md, false",
        "sluenc, false",
        "notes.sluenc.txt, false",
    )
    fun `only a regular file with the extension can be opened`(
        name: String,
        expected: Boolean,
    ) {
        val file = Files.createFile(dir.resolve(name))

        assertEquals(expected, viewer().canOpen(file))
    }

    @Test
    fun `a folder or a missing file named sluenc cannot be opened`() {
        assertFalse(viewer().canOpen(Files.createDirectory(dir.resolve("folder.sluenc"))))
        assertFalse(viewer().canOpen(dir.resolve("missing.sluenc")))
    }

    @Test
    fun `an unreadable file cannot be opened`() {
        val file = Files.createFile(dir.resolve("secret.sluenc"))
        val changed = runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------")) }
        assumeTrue(changed.isSuccess && !Files.isReadable(file), "permissions are not enforced here")

        assertFalse(viewer().canOpen(file))
    }

    @Test
    fun `a view that cannot be made closes the key handed over for the file`() {
        val file = dir.resolve("a.sluenc")
        val key = Sluenc.create("long enough password".toCharArray(), ByteArray(0), KdfParams(64, 1, 1)).key
        files.handOver(file, key)

        val failure = assertFailsWith<IllegalStateException> { viewer().open(file) }

        assertEquals("no viewers", failure.message)
        assertTrue(key.zeroed())
        assertNull(files.takeHandover(file), "the handover is taken")
    }

    @Test
    fun `a view that cannot be made without a handover fails as it is`() {
        val failure = assertFailsWith<IllegalStateException> { viewer().open(dir.resolve("b.sluenc")) }

        assertEquals("no viewers", failure.message)
    }
}
