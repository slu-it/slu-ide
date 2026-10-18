package dev.sluide.git.parse

import dev.sluide.git.GitHead
import dev.sluide.git.GitStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitStatusParserTest {
    private fun parse(vararg records: String) = GitStatusParser.parse(records.joinToString("") { "$it\u0000" })

    private fun head(vararg records: String) = GitStatusParser.parseHead(records.joinToString("") { "$it\u0000" })

    @Test
    fun `the branch header gives the branch and commit, other headers are ignored`() {
        val output =
            arrayOf(
                "# branch.oid 5f070b9aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "# branch.head master",
                "# branch.upstream origin/master",
                "# branch.ab +1 -0",
                "1 .M N... 100644 100644 100644 e69de29 e69de29 changed.kt",
            )
        val head = head(*output)
        assertEquals(GitHead("master", "5f070b9aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), head)
        assertEquals("5f070b9", head?.shortCommit)
        assertEquals(mapOf("changed.kt" to GitStatus.MODIFIED), parse(*output))
    }

    @Test
    fun `a renamed file's original path cannot be taken for a header`() {
        val output =
            arrayOf(
                "# branch.oid 5f070b9aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "# branch.head master",
                "2 R. N... 100644 100644 100644 e69de29 e69de29 R100 new.kt",
                "# branch.head x",
            )
        assertEquals(GitHead("master", "5f070b9aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), head(*output))
    }

    @Test
    fun `a detached head has no branch`() {
        assertEquals(
            GitHead(null, "5f070b9aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            head("# branch.oid 5f070b9aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "# branch.head (detached)"),
        )
    }

    @Test
    fun `a repository without commits has a branch but no commit`() {
        val head = head("# branch.oid (initial)", "# branch.head main")
        assertEquals(GitHead("main", null), head)
        assertNull(head?.shortCommit)
    }

    @Test
    fun `without a branch header there is no head`() {
        assertNull(head("? new.kt"))
    }

    @Test
    fun `ordinary changes are added or modified depending on the index`() {
        val statuses =
            parse(
                "1 A. N... 000000 100644 100644 0000000 e69de29 new.kt",
                "1 AM N... 000000 100644 100644 0000000 e69de29 src/new and changed.kt",
                "1 .M N... 100644 100644 100644 e69de29 e69de29 changed.kt",
                "1 M. N... 100644 100644 100644 e69de29 d00491f staged.kt",
                "1 .D N... 100644 100644 000000 e69de29 e69de29 gone.kt",
                "1 D. N... 100644 000000 000000 e69de29 0000000 removed.kt",
            )
        assertEquals(
            mapOf(
                "new.kt" to GitStatus.ADDED,
                "src/new and changed.kt" to GitStatus.ADDED,
                "changed.kt" to GitStatus.MODIFIED,
                "staged.kt" to GitStatus.MODIFIED,
                "gone.kt" to GitStatus.MODIFIED,
                "removed.kt" to GitStatus.MODIFIED,
            ),
            statuses,
        )
    }

    @Test
    fun `renames and copies add their new path and skip the original one`() {
        val statuses =
            parse(
                "2 R. N... 100644 100644 100644 e69de29 e69de29 R100 docs/neu.md",
                "docs/old.md",
                "2 C. N... 100644 100644 100644 e69de29 e69de29 C100 copy.kt",
                "original.kt",
                "1 .M N... 100644 100644 100644 e69de29 e69de29 original.kt",
            )
        assertEquals(
            mapOf(
                "docs/neu.md" to GitStatus.ADDED,
                "copy.kt" to GitStatus.ADDED,
                "original.kt" to GitStatus.MODIFIED,
            ),
            statuses,
        )
    }

    @Test
    fun `conflicts are modified`() {
        val statuses = parse("u UU N... 100644 100644 100644 100644 e69de29 d00491f 0cfbf08 merge me.kt")
        assertEquals(mapOf("merge me.kt" to GitStatus.MODIFIED), statuses)
    }

    @Test
    fun `untracked and ignored paths keep the trailing slash of whole folders`() {
        val statuses = parse("? notes.txt", "? new folder/", "! build/", "! .DS_Store", "? grüße/€.txt")
        assertEquals(
            mapOf(
                "notes.txt" to GitStatus.UNVERSIONED,
                "new folder/" to GitStatus.UNVERSIONED,
                "build/" to GitStatus.IGNORED,
                ".DS_Store" to GitStatus.IGNORED,
                "grüße/€.txt" to GitStatus.UNVERSIONED,
            ),
            statuses,
        )
    }

    @Test
    fun `intent-to-add entries are added`() {
        val statuses =
            parse(
                "1 .A N... 000000 000000 100644 0000000 0000000 planned.kt",
                "1 .M N... 100644 100644 100644 e69de29 e69de29 changed.kt",
            )
        assertEquals(mapOf("planned.kt" to GitStatus.ADDED, "changed.kt" to GitStatus.MODIFIED), statuses)
    }

    @Test
    fun `records too short for a path are skipped`() {
        val statuses =
            parse(
                "?",
                "!",
                "? ",
                "1 .M N... 100644",
                "u UU N... 100644",
                "2 R. N... 100644 100644 100644 e69de29 e69de29 R100",
                "original.kt",
                "? notes.txt",
            )
        assertEquals(mapOf("notes.txt" to GitStatus.UNVERSIONED), statuses)
    }

    @Test
    fun `headers and empty output give no statuses`() {
        assertEquals(emptyMap(), GitStatusParser.parse(""))
        assertEquals(emptyMap(), parse("# branch.oid 1234", "# branch.head master"))
    }
}
