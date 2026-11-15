package dev.sluide.java

import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.localmodels.Checksum
import dev.sluide.servertools.HttpAnswer
import dev.sluide.servertools.UpdateCheck
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The update check against fake answers shaped like the real ones of download.eclipse.org on 2026-11-15 (the index
 * trimmed to a few rows of its table, `latest.txt` and the `.sha256` byte for byte, the `HEAD` of the archive as
 * the host answers it); the comparison with the installed version and the pin is tested in server-tools (`ToolUpdatesTest`).
 */
class EclipseMilestonesTest {
    @TempDir
    lateinit var dir: Path

    private val tarball = "jdt-language-server-1.63.0-202610191322.tar.gz"
    private val sha = "b1f2abe0b3cccb462cf64fb4a5587c7d09df60cf8be7748b152cbd89d018bdc4"
    private val requests = mutableListOf<Pair<URI, Map<String, String>>>()

    private fun row(href: String) =
        "<tr><td></td><td><i class='fa fa-folder' aria-hidden='true'></i> <a href='$href'> ${href.substringAfterLast(
            '/',
        )}" +
            "</a> </td><td style=\"text-align: right; padding-right:15px;\"> 70.5M </td><td>2022-04-13 15:30</td><td></td></tr>"

    /** The real index's table: `..` first, versions as text (so 1.10.0 before 1.9.0), `repository` last. */
    private fun index(vararg extraRows: String) =
        HttpAnswer(
            200,
            emptyMap(),
            "<html><body><h2>Directory Contents</h2><div id='dirlist'><table class='.table'>" +
                listOf(
                    "/jdtls/milestones/..",
                    "/jdtls/milestones/0.1.0",
                    "/jdtls/milestones/1.10.0",
                ).joinToString("") { row(it) } +
                extraRows.joinToString("") +
                listOf(
                    "/jdtls/milestones/1.62.0",
                    "/jdtls/milestones/1.63.0",
                    "/jdtls/milestones/1.8.0",
                    "/jdtls/milestones/1.9.0",
                    "/jdtls/milestones/repository",
                ).joinToString("") { row(it) } +
                "</table></div></body></html>",
        )

    private fun text(body: String) = HttpAnswer(200, emptyMap(), body)

    /** The HEAD answer of the archive: a `content-length` and no body (checked against the real host). */
    private fun sized(contentLength: String? = "51143565") =
        HttpAnswer(200, listOfNotNull(contentLength?.let { "content-length" to it }).toMap(), "")

    private val heads = mutableListOf<URI>()

    private val base = "https://download.eclipse.org/jdtls/milestones/"

    private fun host(
        index: HttpAnswer = index(),
        latest: HttpAnswer = text("$tarball\n"),
        checksum: HttpAnswer = text(sha),
    ): (URI, Map<String, String>) -> HttpAnswer =
        { uri, headers ->
            requests += uri to headers
            when (uri.toString()) {
                base -> index
                "${base}1.63.0/latest.txt" -> latest
                "${base}1.63.0/$tarball.sha256" -> checksum
                "${base}1.63.0/$tarball" -> error("the archive is never fetched with GET, its body is 51 MB")
                else -> HttpAnswer(404, emptyMap(), "")
            }
        }

    private fun check(
        fetch: (URI, Map<String, String>) -> HttpAnswer,
        installed: String = "1.62.0",
        platform: String = "linux-aarch64",
        head: HttpAnswer = sized(),
    ): UpdateCheck {
        var result: UpdateCheck? = null
        JdtlsUpdates(
            JdtlsTool.tool(platform = platform, root = dir.resolve("tools/jdtls")),
            fetch,
            { uri, _ ->
                heads += uri
                head
            },
            Runnable::run,
            { it.run() },
        ).check(installed) { result = it }
        return checkNotNull(result)
    }

    @ParameterizedTest
    @ValueSource(strings = ["linux-x64", "linux-aarch64", "mac-aarch64"])
    fun `the newest milestone by number comes with the one archive for every platform, its sha256 and its size`(
        platform: String,
    ) {
        val available = assertIs<UpdateCheck.Available>(check(host(), platform = platform))

        assertEquals("1.63.0", available.version)
        assertTrue(available.newerThanPinned.not(), "1.63.0 is the pin")
        assertNull(available.releaseNotes)
        val release = available.release
        assertEquals(URI("${base}1.63.0/$tarball"), release.url)
        assertEquals(51_143_565L, release.bytes)
        assertEquals(Checksum.Sha256(sha), release.checksum)
        assertEquals(ArchiveFormat.TAR_GZ, release.format)
        assertEquals("bin/jdtls", release.executable)
    }

    @Test
    fun `the size is the content-length of a HEAD of the archive and the pages are fetched from the milestones only`() {
        check(host())

        assertEquals(
            listOf(base, "${base}1.63.0/latest.txt", "${base}1.63.0/$tarball.sha256"),
            requests.map { it.first.toString() },
        )
        assertEquals(listOf(URI("${base}1.63.0/$tarball")), heads)
    }

    @Test
    fun `an installed newest version is up to date and nothing but the index is fetched`() {
        assertEquals(UpdateCheck.UpToDate("1.63.0"), check(host(), installed = "1.63.0"))
        assertEquals(listOf(base), requests.map { it.first.toString() })
    }

    @Test
    fun `hrefs outside the milestones folder and absurd versions are not versions`() {
        val hostile =
            listOf(
                "https://evil.example/jdtls/milestones/9.9.9",
                "//evil.example/jdtls/milestones/9.9.9",
                "/other/milestones/9.9.9",
                "/jdtls/milestones/9.9.9/extra",
                "/jdtls/milestones/9.9.9?x=1",
                "/jdtls/milestones/9.9.9-evil",
                "/jdtls/milestones/99999999999999999999.0",
                "/jdtls/milestones/9",
                "/jdtls/milestones/1.2.3.4.5",
                "../9.9.9",
                "9.9.9",
            ).map(::row).toTypedArray()

        val available = assertIs<UpdateCheck.Available>(check(host(index = index(*hostile))))

        assertEquals("1.63.0", available.version)
        assertTrue(requests.none { "evil" in it.first.toString() || "9.9.9" in it.first.toString() })
    }

    @Test
    fun `an index without a version fails`() {
        val empty = text("<table><tr><td><a href='/jdtls/milestones/repository'>repository</a></td></tr></table>")

        assertEquals(
            UpdateCheck.Failed("download.eclipse.org lists no jdtls version"),
            check(host(index = empty)),
        )
    }

    @Test
    fun `an index that is not answered with 200 fails with the status`() {
        assertEquals(
            UpdateCheck.Failed("download.eclipse.org answered with HTTP status 503"),
            check(host(index = HttpAnswer(503, emptyMap(), ""))),
        )
    }

    @Test
    fun `latest txt and sha256 answered with an error status fail with it`() {
        assertEquals(
            "download.eclipse.org answered with HTTP status 503",
            assertIs<UpdateCheck.Failed>(check(host(latest = HttpAnswer(503, emptyMap(), "")))).message,
        )
        assertEquals(
            "download.eclipse.org answered with HTTP status 503",
            assertIs<UpdateCheck.Failed>(check(host(checksum = HttpAnswer(503, emptyMap(), "")))).message,
        )
    }

    @Test
    fun `a missing latest txt fails and names it`() {
        val failed = assertIs<UpdateCheck.Failed>(check(host(latest = HttpAnswer(404, emptyMap(), ""))))

        assertEquals("download.eclipse.org has no latest.txt for jdtls 1.63.0", failed.message)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "../../evil.tar.gz",
            "jdt-language-server-1.62.0-202610131111.tar.gz",
            "jdt-language-server-1.63.0-202610191322.tar.gz.exe",
            "https://evil.example/jdt-language-server-1.63.0-202610191322.tar.gz",
            "<html>404</html>",
        ],
    )
    fun `a latest txt that does not name this version's tarball fails`(body: String) {
        val failed = assertIs<UpdateCheck.Failed>(check(host(latest = text(body))))

        assertEquals("download.eclipse.org's latest.txt of jdtls 1.63.0 does not name its archive", failed.message)
        assertTrue(requests.none { "evil" in it.first.toString() })
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "not hex", "ZZ2abe0b3cccb462cf64fb4a5587c7d09df60cf8be7748b152cbd89d018bdc4", "b1f2ab"])
    fun `a sha256 that is not 64 hex digits fails`(body: String) {
        val failed = assertIs<UpdateCheck.Failed>(check(host(checksum = text(body))))

        assertEquals("download.eclipse.org's sha256 of $tarball is not a hash", failed.message)
    }

    @Test
    fun `an upper case hash is lower-cased and a missing sha256 file fails`() {
        val available = assertIs<UpdateCheck.Available>(check(host(checksum = text("${sha.uppercase()}\n"))))
        assertEquals(Checksum.Sha256(sha), available.release.checksum)

        val failed = assertIs<UpdateCheck.Failed>(check(host(checksum = HttpAnswer(404, emptyMap(), ""))))
        assertEquals("download.eclipse.org has no sha256 for $tarball", failed.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "0", "x", "-5", "1e9", "99999999999999999999"])
    fun `a HEAD answer without a usable content-length fails`(contentLength: String) {
        val failed = assertIs<UpdateCheck.Failed>(check(host(), head = sized(contentLength.ifEmpty { null })))

        assertEquals("download.eclipse.org did not say how large $tarball is", failed.message)
    }

    @Test
    fun `a HEAD that is not answered with 200 fails`() {
        val failed =
            assertIs<UpdateCheck.Failed>(check(host(), head = HttpAnswer(405, mapOf("content-length" to "5"), "")))

        assertEquals("download.eclipse.org did not say how large $tarball is", failed.message)
    }

    @Test
    fun `a timeout and an unreachable host become messages`() {
        assertEquals(
            UpdateCheck.Failed("download.eclipse.org did not answer within 15 s"),
            check({ _, _ -> throw HttpTimeoutException("slow") }),
        )
        assertEquals(
            UpdateCheck.Failed("Cannot reach download.eclipse.org: offline"),
            check({ _, _ -> throw IOException("offline") }),
        )
    }
}
