package dev.sluide.ide.github

import dev.sluide.git.RemoteRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteHostsTest {
    private fun urls(origin: String?) =
        remoteRepositories(origin?.let(::originHost), "Actions", "Checkout").map { it.host to it.url }

    @Test
    fun `without an origin only github com is asked, by https, with owner and name lower-cased`() {
        assertEquals(
            listOf(RemoteRepository("github.com", "actions", "checkout", "https://github.com/actions/checkout.git")),
            remoteRepositories(null, "Actions", "Checkout"),
        )
    }

    @Test
    fun `an origin on github com decides only how github com is reached`() {
        val https = listOf("github.com" to "https://github.com/actions/checkout.git")
        assertEquals(https, urls("https://github.com/me/project.git"))
        assertEquals(https, urls("https://github.com:443/me/project.git"))
        assertEquals(https, urls("http://GitHub.com/me/project"))
        assertEquals(
            listOf("github.com" to "git@github.com:actions/checkout.git"),
            urls("git@github.com:me/project.git"),
        )
        assertEquals(
            listOf("github.com" to "ssh://git@github.com/actions/checkout.git"),
            urls("ssh://git@github.com/me/project.git"),
        )
    }

    @Test
    fun `a GitHub Enterprise Server origin is asked first, as the origin is reached, then github com by https`() {
        val github = "github.com" to "https://github.com/actions/checkout.git"
        assertEquals(
            listOf("ghes.example.com" to "https://ghes.example.com/actions/checkout.git", github),
            urls("https://GHES.example.com/team/project.git"),
        )
        assertEquals(
            listOf("ghes.example.com:8443" to "https://ghes.example.com:8443/actions/checkout.git", github),
            urls("https://ghes.example.com:8443/team/project.git"),
        )
        assertEquals(
            listOf("ghes.example.com" to "https://ghes.example.com/actions/checkout.git", github),
            urls("http://ghes.example.com:8080/team/project.git"),
        )
        assertEquals(
            listOf("ghes.example.com" to "git@ghes.example.com:actions/checkout.git", github),
            urls("git@ghes.example.com:team/project.git"),
        )
        assertEquals(
            listOf("ghes.example.com" to "ssh://admin@ghes.example.com:2222/actions/checkout.git", github),
            urls("ssh://admin@ghes.example.com:2222/team/project.git"),
        )
        assertEquals(
            listOf("ghes.example.com" to "ssh://ghes.example.com/actions/checkout.git", github),
            urls("ghes.example.com:team/project.git"),
        )
    }

    @Test
    fun `credentials, paths and queries of the origin never end up in a URL`() {
        val origins =
            listOf(
                "https://user:s3cret@ghes.example.com/team/project.git?token=s3cret",
                "https://s3cret@ghes.example.com:8443/team/project.git#s3cret",
                "https://user:s3cret@github.com/team/project.git",
            )
        for (origin in origins) {
            val found = urls(origin)
            assertTrue(found.none { (host, url) -> "s3cret" in host + url || "team" in url || "user" in url }, "$found")
        }
        assertEquals(
            listOf(
                "ghes.example.com:8443" to "https://ghes.example.com:8443/actions/checkout.git",
                "github.com" to "https://github.com/actions/checkout.git",
            ),
            urls("https://s3cret@ghes.example.com:8443/team/project.git#s3cret"),
        )
    }

    @Test
    fun `origins that are no https or ssh URL of a host count as none`() {
        val unusable =
            listOf(
                "",
                "/home/me/project.git",
                "./project",
                "file:///home/me/project.git",
                "git://ghes.example.com/team/project.git",
                "ext::ssh -i key ghes.example.com %S team/project",
                "ssh://user:s3cret@ghes.example.com/team/project.git",
                "https://user:s3/cret@ghes.example.com/team/project.git",
                "https://[::1]/team/project.git",
                "https://ghes.example.com:port/team/project.git",
                "-uhttps://ghes.example.com/team/project.git",
            )
        for (origin in unusable) {
            assertNull(originHost(origin), origin)
            assertEquals(listOf("github.com" to "https://github.com/actions/checkout.git"), urls(origin), origin)
        }
    }
}
