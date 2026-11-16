package dev.sluide.git

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteUrlsTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "https://github.com/x/y.git|https://github.com",
            "https://u:t@github.com/x.git|https://github.com",
            "https://u:pa%40ss@github.com:8443/x?q=1#f|https://github.com",
            "HTTPS://GitHub.com/x|https://GitHub.com",
            "http://token@host/x|http://host",
            "git://host/x|git://host",
            "ssh://git@host:2222/x|ssh host",
            "ssh://host/x|ssh host",
            "git+ssh://git@host/x|ssh host",
            "ssh+git://host/x|ssh host",
            "git@github.com:x/y.git|ssh github.com",
            "github.com:x/y.git|ssh github.com",
            "git@host:2222/x|ssh host",
            "git@[::1]:x/y|ssh [::1]",
            "https://[::1]:8080/x|https://[::1]",
            "https://u:p@[2001:db8::1]/x|https://[2001:db8::1]",
            "https://host/pa@th|https://host",
            "https://host?a=u:p@x|https://host",
            "file:///srv/repo|Local repository",
            "FILE:///srv/repo|Local repository",
            "/srv/repo|Local repository",
            "../repo|Local repository",
            "repo|Local repository",
            "./a:b|Local repository",
            "C:\\Users\\me\\repo|Local repository",
            "d:/repo|Local repository",
            "\\\\server\\share\\repo|Local repository",
            "  https://github.com/x  |https://github.com",
            // git takes the port and the user inside the brackets too (`ssh -p 2222 git@example.com`)
            "[git@example.com:2222]:x/y|ssh example.com",
            "git@[example.com:2222]:x/y|ssh example.com",
            "[u:secret@h:22]:x|ssh h",
            "[example.com]:x|ssh example.com",
            "[::1]:x|ssh [::1]",
            "u:secret@[::1]:x|ssh [::1]",
        ],
    )
    fun `the location keeps scheme and host and nothing else`(
        url: String,
        expected: String,
    ) {
        assertEquals(expected, remoteLocation(url))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "   ", "ext::sh -c 'echo secret'", "fd::17", "https://", "https://u:p@/x", "https:///x",
            "https://ho st/x", "://host/x", "ssh://@/x", "git@:x", "https://[::1/x", "[:22]:x",
        ],
    )
    fun `an unparseable or unknown location never shows the raw text`(url: String) {
        assertEquals("Unknown location", remoteLocation(url))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "https://u:p@h/x|https://****@h/x",
            "https://token@github.com/x.git|https://****@github.com/x.git",
            "http://token@h|http://****@h",
            "ftp://u@h/x|ftp://****@h/x",
            "ftps://u@h/x|ftps://****@h/x",
            "HTTPS://token@h/x|HTTPS://****@h/x",
            "ssh://u:p@h/x|ssh://****@h/x",
            "git://u:p@h/x|git://****@h/x",
            "https://u:pa%40ss@h/x|https://****@h/x",
            "https://u:p@ss@h/x|https://****@h/x",
            "https://u:p@[::1]:80/x|https://****@[::1]:80/x",
            "https://h/pa:th@x|https://h/pa:th@x",
            "https://h:80/x|https://h:80/x",
            "https://@h/x|https://@h/x",
            "ssh://git@host/x|ssh://git@host/x",
            "git@host:x/y|git@host:x/y",
            "/srv/repo|/srv/repo",
            "ext::sh -c x|ext::sh -c x",
            "|",
            "  https://u:p@h/x  |https://****@h/x",
            "hg::https://u:pw@h/r|hg::https://****@h/r",
            "hg::ssh://git@h/r|hg::ssh://git@h/r",
            "hg::ssh://u:pw@h/r|hg::ssh://****@h/r",
            "[u:secret@h:22]:x|[****@h:22]:x",
            "[git@example.com:2222]:x/y|[git@example.com:2222]:x/y",
            "git@[example.com:2222]:x/y|git@[example.com:2222]:x/y",
            "u:secret@[h]:x|****@[h]:x",
            "[::1]:x|[::1]:x",
        ],
    )
    fun `masks the userinfo of http-like URLs and of any URL with a password`(
        url: String?,
        expected: String?,
    ) {
        assertEquals(expected.orEmpty(), maskedUrl(url.orEmpty()))
    }

    @Test
    fun `puts the original userinfo back where the mask still is and the host is the same`() {
        val original = "https://u:p%40ss@host/x.git"
        assertEquals(original, unmaskedUrl(maskedUrl(original), original))
        assertEquals("https://u:p%40ss@host/y.git", unmaskedUrl("https://****@host/y.git", original))
        assertEquals("https://u:p%40ss@HOST/x.git", unmaskedUrl("https://****@HOST/x.git", original))
        assertEquals("ftp://tok@h/", unmaskedUrl("ftp://****@h/", "ftp://tok@h/"))
        assertEquals("hg::https://u:pw@h/r2", unmaskedUrl("hg::https://****@h/r2", "hg::https://u:pw@h/r"))
        assertEquals("[u:secret@h:22]:y", unmaskedUrl("[****@h:22]:y", "[u:secret@h:22]:x"))
        assertEquals("u:secret@[h]:y", unmaskedUrl("****@[h]:y", "u:secret@[h]:x"))
    }

    @Test
    fun `an edited URL is trimmed like the others`() {
        val original = "https://u:p@host/x"
        assertEquals("https://u:p@host/y", unmaskedUrl("  https://****@host/y ", original))
        assertEquals("https://new@h/y", unmaskedUrl(" https://new@h/y ", original))
        assertNull(unmaskedUrl(" https://****@other/y ", original))
    }

    @Test
    fun `the original userinfo is not put back at another host, port or scheme`() {
        val original = "https://u:p@host:8443/x.git"
        assertNull(unmaskedUrl("https://****@other/x.git", original))
        assertNull(unmaskedUrl("https://****@host:9000/x.git", original))
        assertNull(unmaskedUrl("https://****@host/x.git", original))
        assertNull(unmaskedUrl("http://****@host:8443/x.git", original))
        assertNull(unmaskedUrl("hg::https://****@other/r", "hg::https://u:pw@h/r"))
        assertNull(unmaskedUrl("https://****@h/r", "hg::https://u:pw@h/r"))
        assertNull(unmaskedUrl("[****@other:22]:x", "[u:secret@h:22]:x"))
    }

    @Test
    fun `hides the userinfo in a text, to the last at sign before the authority ends`() {
        assertEquals(
            "fatal: could not set 'remote.o.url' to 'https://***@h.example/x'",
            withoutUserinfoIn("fatal: could not set 'remote.o.url' to 'https://user:s3cret@h.example/x'", "***"),
        )
        assertEquals(
            "a ssh://***@h/x b hg::https://***@h/r",
            withoutUserinfoIn("a ssh://u@h/x b hg::https://u:p@ss@h/r", "***"),
        )
        assertEquals(
            "no url, git@host:x and https://h/p@th",
            withoutUserinfoIn("no url, git@host:x and https://h/p@th", "***"),
        )
        assertEquals("https://***@h", withoutUserinfoIn("https://u:p@h", "***"))
        // an apostrophe is legal in userinfo (measured: git prints it inside its own quotes)
        assertEquals(
            "fatal: could not set 'remote.n.url' to 'https://***@h.example/y.git'",
            withoutUserinfoIn("fatal: could not set 'remote.n.url' to 'https://user:pa'ss@h.example/y.git'", "***"),
        )
        assertEquals("'https://***@h/x'", withoutUserinfoIn("'https://user:pa'ss@h/x'", "***"))
    }

    @Test
    fun `hiding the userinfo in a long text without URLs does not take long`() {
        // 200 KB of letters, as a test of the git process wrapper makes git say; a regex that tries a scheme at every
        // letter is quadratic here.
        val text = "e".repeat(200_000)

        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            assertEquals(text, withoutUserinfoIn(text, "***"))
        }
    }

    @Test
    fun `hides the userinfo of an argument also behind a transport, and trims it`() {
        assertEquals("hg::https://***@h/r", withoutUserinfo("hg::https://u:pw@h/r", "***"))
        assertEquals("https://***@h/r", withoutUserinfo("  https://u:pw@h/r ", "***"))
        assertEquals("ext::sh -c x", withoutUserinfo("ext::sh -c x", "***"))
        assertEquals("[***@h:22]:x", withoutUserinfo("[u:secret@h:22]:x", "***"))
        assertEquals("***@[h]:x", withoutUserinfo("u:secret@[h]:x", "***"))
        assertEquals("[git@h:22]:x", withoutUserinfo("[git@h:22]:x", "***"))
        assertEquals("git@[h:22]:x", withoutUserinfo("git@[h:22]:x", "***"))
        assertEquals("a [***@h:22]:x b [git@h:22]:y", withoutUserinfoIn("a [u:secret@h:22]:x b [git@h:22]:y", "***"))
    }

    @Test
    fun `the URLs of a remote are masked in toString`() {
        val text = GitRemoteUrls("https://user:s3cret@h/x", "https://tok3n@h/p", false, false).toString()

        assertFalse("s3cret" in text || "tok3n" in text, text)
        assertTrue("https://****@h/x" in text && "https://****@h/p" in text, text)
    }

    @Test
    fun `an edited userinfo, or an original without a masked one, is taken as written`() {
        val original = "https://u:p@host/x"
        assertEquals("https://new:pw@host/x", unmaskedUrl("https://new:pw@host/x", original))
        assertEquals("https://host/x", unmaskedUrl("https://host/x", original))
        assertEquals("https://****@host/x", unmaskedUrl("https://****@host/x", "/srv/repo"))
        assertEquals("https://**@host/x", unmaskedUrl("https://**@host/x", original))
        assertEquals("https://****@host/x", unmaskedUrl("https://****@host/x", "https://host/x"))
        // ssh://git@host is not masked, so a user who types the mask there means it.
        assertEquals("ssh://****@host/x", unmaskedUrl("ssh://****@host/x", "ssh://git@host/x"))
        assertEquals("/srv/repo", unmaskedUrl("/srv/repo", original))
        assertEquals("git@host:x", unmaskedUrl("git@host:x", original))
    }

    @Test
    fun `the mask never contains what it hides`() {
        val hidden = listOf("https://user:s3cret@h/x", "https://s3cret@h/x", "ssh://user:s3cret@h/x")
        for (url in hidden) {
            assertFalse("s3cret" in maskedUrl(url), url)
            assertFalse("s3cret" in remoteLocation(url), url)
        }
    }
}
