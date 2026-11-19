package dev.sluide.git.parse

import dev.sluide.git.GitPushRejection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PushPorcelainTest {
    private val refs = "refs/heads/a:refs/heads/b"

    @Test
    fun `the status line of a refspec is its flag and summary, the first line that names it`() {
        val output =
            "To host\n" +
                "*\trefs/heads/x:refs/heads/x\t[new branch]\n" +
                "!\t$refs\t[rejected] (non-fast-forward)\n" +
                "=\t$refs\tsecond\n" +
                "Done\n"

        val status = pushStatus(output, refs)

        assertEquals('!', status?.flag)
        assertEquals("[rejected] (non-fast-forward)", status?.summary)
        assertEquals('*', pushStatus(output, "refs/heads/x:refs/heads/x")?.flag)
        assertNull(pushStatus(output, "refs/heads/other:refs/heads/other"))
        assertNull(pushStatus("", refs))
    }

    @Test
    fun `a line is a status line only with a one-character flag, the refspec and a summary`() {
        assertNull(pushStatus("!\t$refs", refs), "no summary")
        assertNull(pushStatus("!!\t$refs\tsummary", refs), "a flag of two characters")
        assertNull(pushStatus("\t$refs\tsummary", refs), "no flag")
        assertNull(pushStatus("!\t$refs-more\tsummary", refs), "another refspec")
        assertNull(pushStatus("! $refs summary", refs), "spaces for tabs")
        assertEquals("a\tb", pushStatus("-\t$refs\ta\tb", refs)?.summary, "tabs in the summary stay")
        assertEquals("", pushStatus("-\t$refs\t", refs)?.summary)
    }

    @Test
    fun `a deleted ref has a status line of its own, or one of git's refusal`() {
        assertEquals('-', deletionStatus("-\t:refs/heads/gone\t[deleted]\n", "refs/heads/gone")?.flag)
        assertEquals(
            '!',
            deletionStatus("!\t(delete):refs/heads/gone\t[rejected] (stale info)\n", "refs/heads/gone")?.flag,
        )
        assertNull(deletionStatus("-\t:refs/heads/other\t[deleted]\n", "refs/heads/gone"))
        assertEquals(
            '-',
            deletionStatus("!\t(delete):refs/heads/gone\tx\n-\t:refs/heads/gone\ty\n", "refs/heads/gone")?.flag,
            ":ref wins over (delete):ref",
        )
    }

    @Test
    fun `the reason of a refusal is the last text in parentheses`() {
        assertEquals("non-fast-forward", reason("[rejected] (non-fast-forward)"))
        assertEquals("b", reason("(a) (b)"))
        assertEquals("", reason("[new branch]"))
        assertEquals("already exists", reason("[rejected] (already exists)"))
        assertEquals("no close", reason("x (no close"))
    }

    @Test
    fun `a rejection is classified by its reason`() {
        assertEquals(GitPushRejection.NON_FAST_FORWARD, rejection("[rejected] (non-fast-forward)"))
        assertEquals(GitPushRejection.NON_FAST_FORWARD, rejection("[rejected] (fetch first)"))
        assertEquals(GitPushRejection.STALE, rejection("[rejected] (stale info)"))
        assertEquals(GitPushRejection.OTHER, rejection("[remote rejected] (pre-receive hook declined)"))
        assertEquals(GitPushRejection.OTHER, rejection("[rejected]"))
        assertEquals(GitPushRejection.OTHER, rejection("[rejected] (non-fast-forward) extra"))
    }

    @Test
    fun `a reason that only has the hash code of a known one is no known reason`() {
        // Same String.hashCode as non-fast-forward, fetch first and stale info.
        for (collision in listOf("oPn-fast-forward", "gFtch first", "tUale info")) {
            assertEquals(GitPushRejection.OTHER, rejection("[rejected] ($collision)"), collision)
        }
    }
}
