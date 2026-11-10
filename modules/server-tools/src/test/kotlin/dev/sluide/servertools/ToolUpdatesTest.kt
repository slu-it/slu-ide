package dev.sluide.servertools

import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.localmodels.Checksum
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The shared update check over a fake source; what GitHub or npm answer is tested in the language modules. */
class ToolUpdatesTest {
    @TempDir
    lateinit var dir: Path

    private val notes = URI("https://example.com/acme/releases/0.1.0")

    private fun updates(
        pinned: String = "0.0.99",
        platform: String? = "linux-aarch64",
        latest: () -> LatestRelease = { acmeRelease("0.1.0") },
    ) = ToolUpdates(acmeTool(dir, platform, pinned), FakeSource(latest), Runnable::run, { it.run() })

    /** The check's answer, compared with [installed] (the pin when null). */
    private fun ToolUpdates.result(installed: String?): UpdateCheck {
        var result: UpdateCheck? = null
        check(installed) { result = it }
        return checkNotNull(result)
    }

    @Test
    fun `a newer release comes with this platform's asset as the tool installs it`() {
        val result = updates(pinned = "0.0.98").result("0.0.98")

        val available = assertIs<UpdateCheck.Available>(result)
        assertEquals("0.1.0", available.version)
        assertEquals(true, available.newerThanPinned)
        assertEquals(notes, available.releaseNotes)
        val release = available.release
        assertEquals("0.1.0", release.version)
        assertEquals(URI("https://example.com/acme/0.1.0/$FAKE_ASSET"), release.url)
        assertEquals(1000, release.bytes)
        assertEquals(Checksum.Sha256("c".repeat(64)), release.checksum)
        assertEquals(ArchiveFormat.TAR_GZ, release.format)
        assertEquals("acme-ls-aarch64-linux/acme-ls", release.executable)
    }

    @Test
    fun `a release newer than the installed one but not than the pin is not newer than tested`() {
        val result = updates(pinned = "0.1.0").result("0.0.97")

        assertEquals(false, assertIs<UpdateCheck.Available>(result).newerThanPinned)
    }

    @ParameterizedTest
    @CsvSource("0.1.0", "0.1.1", "1.0")
    fun `no newer release is up to date with the version compared, without looking for its asset`(compared: String) {
        val release = acmeRelease("0.1.0")
        val noAsset = LatestRelease.Release(release.version, release.releaseNotes) { error("asked for the asset") }

        assertEquals(UpdateCheck.UpToDate(compared), updates { noAsset }.result(compared))
    }

    @Test
    fun `a prerelease is not offered`() {
        assertEquals(
            UpdateCheck.UpToDate("0.0.98"),
            updates { acmeRelease("0.1.0", prerelease = true) }.result("0.0.98"),
        )
    }

    @Test
    fun `a newer release without an asset for this platform has no build`() {
        assertEquals(UpdateCheck.NoBuild("0.1.0", notes), updates(platform = null).result("0.0.98"))
        assertEquals(UpdateCheck.NoBuild("0.1.0", notes), updates(platform = "linux-x64").result("0.0.98"))
    }

    @Test
    fun `what the source cannot use is the check's failure`() {
        val unusableAsset = LatestRelease.Release("0.1.0", notes) { PlatformAsset.Failed("no checksum") }

        assertEquals(UpdateCheck.Failed("no checksum"), updates { unusableAsset }.result("0.0.98"))
        assertEquals(
            UpdateCheck.Failed("Acme answered 502"),
            updates { LatestRelease.Failed("Acme answered 502") }.result("0.0.98"),
        )
    }

    @ParameterizedTest
    @CsvSource("../x", "''", "-1", "1.0/x")
    fun `a version that cannot name a folder is refused`(version: String) {
        assertEquals(
            UpdateCheck.Failed("Acme's latest acme-ls release has no usable version"),
            updates { acmeRelease(version) }.result("0.0.98"),
        )
    }

    @Test
    fun `a check compares with the installed version, else the pin, and fails readably without the source`() {
        val results =
            listOf(
                updates(pinned = "0.1.0").result(null),
                updates().result("0.0.98"),
                updates { throw IOException("connection refused") }.result("0.0.98"),
                updates { throw HttpTimeoutException("timed out") }.result("0.0.98"),
                updates { error("broken") }.result("0.0.98"),
            )

        assertEquals(UpdateCheck.UpToDate("0.1.0"), results[0])
        assertIs<UpdateCheck.Available>(results[1])
        assertEquals(UpdateCheck.Failed("Cannot reach Acme: connection refused"), results[2])
        assertEquals(UpdateCheck.Failed("Acme did not answer within 15 s"), results[3])
        assertEquals(UpdateCheck.Failed("The check failed: broken"), results[4])
    }

    @Test
    fun `an asset lookup that cannot reach the source fails like the first request`() {
        fun failingAsset(failure: Exception) = LatestRelease.Release("0.1.0", notes) { throw failure }

        assertEquals(
            UpdateCheck.Failed("Cannot reach Acme: connection refused"),
            updates { failingAsset(IOException("connection refused")) }.result("0.0.98"),
        )
        assertEquals(
            UpdateCheck.Failed("Acme did not answer within 15 s"),
            updates { failingAsset(HttpTimeoutException("timed out")) }.result("0.0.98"),
        )
    }
}
