package dev.sluide.gitlog.operations

import dev.sluide.git.GitRemoteUrls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteDialogsTest {
    @Test
    fun `printing what a dialog returns masks the credentials of its URLs`() {
        val texts =
            listOf(
                NewRemote("upstream", "https://u:token@example.org/r.git", true),
                UrlsEdit(null, "https://u:token@example.org/r.git", "https://u2:t2@example.org/p.git", false),
            ).map { it.toString() }
        for (text in texts) assertTrue("token" !in text && "t2" !in text && "****" in text, text)
    }

    private val remotes = listOf("origin", "team/a")

    private fun name(
        name: String,
        except: String? = null,
        unchanged: String? = null,
        canPass: Boolean = true,
    ) = remoteNameProblem(name, remotes, { canPass }, except, unchanged)

    @Test
    fun `a remote name's problem is told`() {
        val problems =
            mapOf(
                "" to "Enter a name.",
                "a b" to "'a b' is not a valid remote name.",
                "a..b" to "'a..b' is not a valid remote name.",
                "origin" to "A remote named 'origin' already exists.",
                "team" to "'team' conflicts with the remote 'team/a'.",
                "origin/x" to "'origin/x' conflicts with the remote 'origin'.",
                "-x" to "A name must not start with '-'.",
            )
        for ((name, problem) in problems) assertEquals(problem, name(name), name)
    }

    @Test
    fun `a good name, one git cannot take, an unchanged one and the renamed one's own prefix`() {
        assertNull(name("upstream"))
        assertNull(name("HEAD"), "git takes it")
        assertEquals("This name cannot be passed to git in this locale.", name("upstream", canPass = false))
        assertEquals("", name("origin", unchanged = "origin"))
        // Renaming team/a to team is no conflict with itself; origin/x still conflicts with origin.
        assertNull(name("team", except = "team/a"))
        assertEquals("'origin/x' conflicts with the remote 'origin'.", name("origin/x", except = "team/a"))
    }

    @Test
    fun `a blank URL is a problem`() {
        assertEquals("Enter a URL.", remoteUrlProblem(""))
        assertEquals("Enter a URL.", remoteUrlProblem("  \t"))
        assertNull(remoteUrlProblem("../x"))
    }

    private val stored = "https://user:token@example.com/x.git"
    private val shown = "https://****@example.com/x.git"

    private fun urls(
        url: String? = stored,
        pushUrl: String? = null,
        severalUrls: Boolean = false,
        severalPushUrls: Boolean = false,
    ) = GitRemoteUrls(url, pushUrl, severalUrls, severalPushUrls)

    @Test
    fun `nothing is changed while both fields show what is stored`() {
        val edit = urlsEdit(urls(pushUrl = "git@host:x.git"), shown, "git@host:x.git")

        assertNull(edit.problem)
        assertFalse(edit.changed)
        assertEquals(UrlsEdit(null, null, null, false), edit)
    }

    @Test
    fun `a changed URL keeps the credentials where the mask stays and takes the typed ones elsewhere`() {
        assertEquals(
            "https://user:token@example.com/y.git",
            urlsEdit(urls(), "https://****@example.com/y.git", "").url,
        )
        assertEquals(
            "https://other:pw@example.org/y.git",
            urlsEdit(urls(), "https://other:pw@example.org/y.git", "").url,
        )
        assertEquals("../local", urlsEdit(urls(), " ../local ", "").url)
    }

    @Test
    fun `the mask on another host asks for credentials`() {
        val edit = urlsEdit(urls(), "https://****@example.org/x.git", "")

        assertEquals("Enter the credentials for the new host, or remove them.", edit.problem)
        assertFalse(edit.changed)
        assertEquals(
            "Enter the credentials for the new host, or remove them.",
            urlsEdit(urls(pushUrl = stored), shown, "https://****@example.org/x.git").problem,
        )
    }

    @Test
    fun `a blank URL is a problem unless several are configured`() {
        assertEquals("Enter a URL.", urlsEdit(urls(), "  ", "").problem)
        assertNull(urlsEdit(urls(severalUrls = true), "", "").problem)
    }

    @Test
    fun `a push URL is set, changed, kept and cleared`() {
        assertEquals("git@host:p.git", urlsEdit(urls(), shown, " git@host:p.git ").pushUrl)
        assertEquals(
            UrlsEdit(null, null, "https://u:t@h/p", false),
            urlsEdit(urls(pushUrl = "https://u:t@h/q"), shown, "https://****@h/p"),
        )
        val kept = urlsEdit(urls(pushUrl = "https://u:t@h/q"), shown, "https://****@h/q")
        assertFalse(kept.changed, "the mask alone writes nothing")
        assertEquals(UrlsEdit(null, null, null, true), urlsEdit(urls(pushUrl = "git@h:q"), shown, "  "))
        assertEquals(UrlsEdit(null, null, null, false), urlsEdit(urls(), shown, ""), "no push URL stays none")
    }

    @Test
    fun `fields with several values are left alone whatever they show`() {
        val edit = urlsEdit(urls(severalUrls = true, severalPushUrls = true, pushUrl = "x"), "changed", "changed too")

        assertEquals(UrlsEdit(null, null, null, false), edit)
    }

    @Test
    fun `a change is any of the URL, the push URL or clearing it`() {
        assertTrue(UrlsEdit(null, "u", null, false).changed)
        assertTrue(UrlsEdit(null, null, "p", false).changed)
        assertTrue(UrlsEdit(null, null, null, true).changed)
        assertFalse(UrlsEdit(null, null, null, false).changed)
    }

    @Test
    fun `both fields can change at once`() {
        val edit = urlsEdit(urls(pushUrl = "git@h:q"), "https://example.com/z", "")

        assertEquals(UrlsEdit(null, "https://example.com/z", null, true), edit)
        assertTrue(edit.changed)
    }

    @Test
    fun `the notes name several URLs and a hidden credential`() {
        val several = "Several URLs are configured; change them in a terminal."
        val hidden = "Credentials are hidden as ****; leave them to keep them."

        assertEquals(hidden, urlsNote(urls(), shown, ""))
        assertNull(urlsNote(urls(), "https://example.com/x.git", ""))
        assertNull(urlsNote(urls(url = "git@h:x"), "git@h:x", ""))
        assertEquals(several, urlsNote(urls(severalPushUrls = true), "https://example.com/x.git", ""))
        // A disabled field shows its mask without the hint to keep it.
        assertEquals(several, urlsNote(urls(severalUrls = true), shown, ""))
        assertEquals("$several\n$hidden", urlsNote(urls(severalPushUrls = true), shown, ""))
    }
}
