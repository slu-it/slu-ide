package dev.sluide.git.cli

import dev.sluide.git.FAKE_GIT_START
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The pure rules that stand in for `git remote remove` and `rename`; real git is compared in GitRemoteConfigTest. */
class GitCliRemotesTest {
    @TempDir
    lateinit var folder: Path

    private fun maps(
        ref: String,
        vararg refspecs: String,
    ) = refspecsMap(refspecs.toList(), ref)

    @Test
    fun `a destination with a wildcard maps by its prefix and its suffix`() {
        assertTrue(maps("refs/remotes/o/a-x", "+refs/heads/*:refs/remotes/o/*-x"))
        assertFalse(maps("refs/remotes/o/abcd", "+refs/heads/*:refs/remotes/o/*-x"))
        assertFalse(maps("refs/remotes/p/a-x", "+refs/heads/*:refs/remotes/o/*-x"))
    }

    @Test
    fun `prefix and suffix of a destination do not overlap`() {
        assertFalse(maps("refs/remotes/o/abc", "refs/heads/*:refs/remotes/o/ab*bc"))
        assertTrue(maps("refs/remotes/o/abbc", "refs/heads/*:refs/remotes/o/ab*bc"))
    }

    @Test
    fun `a wildcard destination with a source that has none maps nothing`() {
        // git refuses to even run with such a refspec ("invalid refspec"), so its removal is moot; no guess either.
        assertFalse(maps("refs/remotes/o/main", "+refs/heads/main:refs/remotes/o/*"))
    }

    @Test
    fun `a negative refspec excludes by prefix and suffix of the source, not by overlap`() {
        val default = "+refs/heads/*:refs/remotes/o/*"
        assertFalse(maps("refs/remotes/o/a-wip", default, "^refs/heads/*-wip"))
        assertTrue(maps("refs/remotes/o/abcdefghi", default, "^refs/heads/*-wip"))
        assertTrue(maps("refs/remotes/o/a-wip", default, "^refs/heads/x*-wip"))
        assertTrue(maps("refs/remotes/o/abc", default, "^refs/heads/ab*bc"))
        assertFalse(maps("refs/remotes/o/abbc", default, "^refs/heads/ab*bc"))
        assertFalse(maps("refs/remotes/o/abc", default, "^refs/heads/a*"))
    }

    @Test
    fun `a refspec without a destination maps nothing and does not read as a negative one`() {
        // `+refs/heads/dev` without a destination minus its first character would read like a negative refspec.
        assertTrue(maps("refs/remotes/o/dev", "+refs/heads/*:refs/remotes/o/*", "+refs/heads/dev"))
        assertFalse(maps("refs/remotes/o/main", "refs/remotes/o/main"))
        assertFalse(maps("refs/remotes/o/main", "+refs/remotes/o/main"))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "refs/remotes/o/main|o|false",
            "+refs/heads/*:refs/remotes/o/*|o|true",
            "+refs/heads/*:refs/remotes/o/*|p|false",
            "+refs/heads/*:refs/remotes/op/*|o|false",
            "^refs/heads/dev|o|false",
            "^refs/heads/x:refs/remotes/o/x|o|false",
            "refs/heads/main:|o|false",
        ],
    )
    fun `a refspec follows a rename only with a destination below the remote`(
        refspec: String,
        old: String,
        expected: Boolean,
    ) {
        assertEquals(expected, refspecsFollowRename(listOf(refspec), old))
    }

    @Test
    fun `a ref is kept when any source it comes from is negated`() {
        val specs = arrayOf("+refs/heads/*:refs/remotes/o/*", "+refs/tags/*:refs/remotes/o/*", "^refs/tags/v1")
        assertFalse(maps("refs/remotes/o/v1", *specs))
        assertTrue(maps("refs/remotes/o/v2", *specs))
    }

    private var fakes = 0

    private fun fakeGit(script: String): GitCli {
        val executable =
            folder.resolve("fake-git-${++fakes}").apply {
                writeText(FAKE_GIT_START + "$script\n")
                toFile().setExecutable(true)
            }
        return GitCli(executable.toString(), System.getenv(), pipeGrace = 300.milliseconds).also {
            assumeTrue(it.available, "no /bin/sh to stand in for git")
        }
    }

    @Test
    fun `a config read of a git that cannot be started is no answer`() {
        val git = fakeGit("exit 0")
        folder.resolve("fake-git-$fakes").toFile().delete()

        assertNull(git.configMatching(folder, "^remote\\."))
    }

    @Test
    fun `a config read whose output is still held open after git ended is no answer`() {
        // Same fake as GitCliWriteTest: a child keeps the pipe open past git's exit.
        val git = fakeGit("(sleep 20 &); printf 'remote.a.url\\nu\\0'; exit 0")

        assertNull(git.configMatching(folder, "^remote\\."))
        assertEquals(
            listOf("remote.a.url" to "u"),
            fakeGit("printf 'remote.a.url\\nu\\0'").configMatching(folder, "^r"),
        )
    }
}
