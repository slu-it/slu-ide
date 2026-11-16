package dev.sluide.git

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
            "https://ho st/x", "://host/x", "ssh://@/x", "git@:x", "https://[::1/x",
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
        ],
    )
    fun `masks the userinfo of http-like URLs and of any URL with a password`(
        url: String?,
        expected: String?,
    ) {
        assertEquals(expected.orEmpty(), maskedUrl(url.orEmpty()))
    }

    @Test
    fun `puts the original userinfo back where the mask still is`() {
        val original = "https://u:p%40ss@host/x.git"
        assertEquals(original, unmaskedUrl(maskedUrl(original), original))
        assertEquals("https://u:p%40ss@other/y.git", unmaskedUrl("https://****@other/y.git", original))
        assertEquals("ftp://tok@h/", unmaskedUrl("ftp://****@h/", "ftp://tok@h/"))
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
