package dev.sluide.git.remote

import dev.sluide.git.RemoteRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteRefusalsTest {
    private fun refusal(
        url: String,
        host: String = "github.com",
        allowFileUrls: Boolean = false,
    ) = repositoryRefusal(RemoteRepository(host, "actions", "checkout", url), allowFileUrls)

    @Test
    fun `accepts https and ssh URLs of the repository without credentials`() {
        val urls =
            listOf(
                "https://github.com/actions/checkout.git",
                "https://github.com/actions/checkout",
                "https://github.com/actions/checkout/",
                "https://GitHub.com/Actions/Checkout.git",
                "ssh://git@github.com/actions/checkout.git",
                "ssh://git@github.com:22/actions/checkout.git",
                "git@github.com:actions/checkout.git",
                "git@github.com:/actions/checkout",
            )
        for (url in urls) {
            assertNull(refusal(url), url)
        }
        assertNull(refusal("https://ghes.example.com:8443/actions/checkout.git", host = "ghes.example.com:8443"))
        assertNull(refusal("git@ghes.example.com:actions/checkout.git", host = "ghes.example.com:8443"))
        assertNull(refusal("file:///srv/actions/checkout", allowFileUrls = true))
    }

    @Test
    fun `refuses other transports, local paths, options and credentials`() {
        val urls =
            listOf(
                "file:///srv/actions/checkout",
                "ext::sh -c 'touch x'",
                "ext::ssh",
                "git://github.com/actions/checkout.git",
                "http://github.com/actions/checkout.git",
                "-oProxyCommand=x",
                "ssh://-oProxyCommand=x/actions/checkout",
                "ssh://-user@github.com/actions/checkout",
                "git@-github.com:actions/checkout.git",
                "git@github.com:-actions/checkout.git",
                "https://user:token@github.com/actions/checkout.git",
                "https://token@github.com/actions/checkout.git",
                "https://-github.com/actions/checkout",
                "https://github.com/actions/checkout b",
                "https://github.com/actions/checkout?x=1",
                "/srv/actions/checkout",
                "../actions/checkout",
                "github.com:actions/checkout",
            )
        for (url in urls) {
            assertEquals("unsupported repository URL", refusal(url), url)
        }
    }

    @Test
    fun `refuses URLs of another repository than the folder's`() {
        val urls =
            listOf(
                "https://evil.example.com/actions/checkout.git",
                "https://github.com:8443/actions/checkout.git",
                "https://github.com/actions/other.git",
                "https://github.com/evil/checkout.git",
                "https://github.com/actions/checkout/x",
                "https://github.com/x/actions/checkout",
                "https://github.com/../actions/checkout",
                "ssh://git@evil.example.com/actions/checkout.git",
                "git@evil.example.com:actions/checkout.git",
                "git@github.com:actions/checkout.git.git",
            )
        for (url in urls) {
            assertEquals("repository URL does not match", refusal(url), url)
        }
        assertEquals(
            "repository URL does not match",
            refusal("file:///srv/actions/other", allowFileUrls = true),
        )
    }

    @Test
    fun `accepts refs that name branches, tags and commits`() {
        for (ref in listOf("main", "v4", "v4.1.2", "release/1.x", "feature_a-b", "a".repeat(40))) {
            assertNull(refRefusal(ref), ref)
        }
    }

    @Test
    fun `maps what git says to short reasons`() {
        assertEquals(
            NO_ACCESS,
            remoteFailure("remote: Repository not found.\nfatal: repository 'https://x/y/' not found", "x"),
        )
        assertEquals(
            NO_ACCESS,
            remoteFailure("fatal: could not read Username for 'https://github.com': terminal prompts disabled", "x"),
        )
        assertEquals(NO_ACCESS, remoteFailure("git@github.com: Permission denied (publickey).", "x"))
        assertEquals(
            NO_ACCESS,
            remoteFailure("fatal: unable to access 'https://x/a/b/': The requested URL returned error: 403", "x"),
        )
        assertEquals(
            NO_ACCESS,
            remoteFailure(
                "fatal: unable to access 'https://x/a/b/': The requested URL returned error: 403 Forbidden",
                "x",
            ),
        )
        assertEquals(
            NETWORK,
            remoteFailure("fatal: unable to access 'https://x/a/b/': The requested URL returned error: 502", "x"),
        )
        assertEquals(NETWORK, remoteFailure("fatal: unable to access 'https://x/': Could not resolve host: x", "x"))
        assertEquals(
            NETWORK,
            remoteFailure(
                "ssh: Could not resolve hostname x: Name or service not known\n" +
                    "fatal: Could not read from remote repository.",
                "x",
            ),
        )
        assertEquals(REF_NOT_FOUND, remoteFailure("fatal: couldn't find remote ref v9", "x"))
        assertEquals("fallback", remoteFailure("fatal: something else", "fallback"))
    }

    @Test
    fun `names in a message and what the server says do not decide the reason`() {
        assertEquals(NO_ACCESS, remoteFailure("fatal: repository 'https://github.com/a/handshake/' not found", "x"))
        assertEquals(
            NO_ACCESS,
            remoteFailure("fatal: unable to access 'https://x/handshake/': The requested URL returned error: 404", "x"),
        )
        assertEquals(REF_NOT_FOUND, remoteFailure("fatal: couldn't find remote ref connection-refused", "x"))
        assertEquals("x", remoteFailure("remote: fatal: couldn't find remote ref v9\nremote: ssh: nope", "x"))
    }
}
