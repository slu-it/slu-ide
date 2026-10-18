package dev.sluide.ide

import dev.sluide.fileexplorer.FileMarker
import dev.sluide.fileexplorer.FileMarkers
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.git.GitStatusService
import dev.sluide.git.GitStatusSnapshot
import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

class GitMarkersTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var scripts: Path

    /**
     * The snapshot that `git status` [records] give for [root]. The snapshot can only be made by the git module, so a
     * script stands in for git and prints them.
     */
    private fun snapshotOf(vararg records: String): GitStatusSnapshot {
        root.resolve(".git").createDirectories()
        val output = scripts.resolve("status").apply { writeText(records.joinToString("") { "$it\u0000" }) }
        val executable =
            scripts.resolve("git").apply {
                writeText("#!/bin/sh\ncat '$output'\n")
                toFile().setExecutable(true)
            }
        val git = GitCli(executable.toString(), System.getenv())
        assumeTrue(git.available, "no /bin/sh to stand in for git")
        val dispatched = LinkedBlockingQueue<Runnable>()
        val watcher = FileWatcher({ dispatched += it }, 50.milliseconds)
        val service = GitStatusService(git, watcher, trustingTrust()) { dispatched += it }
        try {
            service.open(root)
            while (service.snapshot.get().root == null) {
                (dispatched.poll(10, TimeUnit.SECONDS) ?: fail("No git status")).run()
            }
            return service.snapshot.get()
        } finally {
            service.close()
            watcher.close()
        }
    }

    private fun path(relative: String) = root.resolve(relative)

    @Test
    fun `git would commit files of the repository that it does not ignore`() {
        val snapshot = snapshotOf("? api/new.json", "! api/http-client.private.env.json", "! secrets/")

        assertTrue(snapshot.wouldCommit(path("api/new.json")))
        assertTrue(snapshot.wouldCommit(path("api/clean.json")))
        assertFalse(snapshot.wouldCommit(path("api/http-client.private.env.json")))
        assertFalse(snapshot.wouldCommit(path("secrets/http-client.private.env.json")))
        assertFalse(snapshot.wouldCommit(root.resolveSibling("elsewhere.json")))
        assertFalse(GitStatusSnapshot.EMPTY.wouldCommit(path("api/new.json")))
    }

    @Test
    fun `files get the colour of their status as name colour`() {
        val snapshot =
            snapshotOf(
                "? scratch.kt",
                "1 A. N... 000000 100644 100644 0000000 e69de29 new.kt",
                "1 .M N... 100644 100644 100644 e69de29 e69de29 changed.kt",
                "! debug.log",
                "! build/",
            )

        assertEquals(FileMarkers(name = FileMarker.UNVERSIONED), snapshot.markers(path("scratch.kt"), false))
        assertEquals(FileMarkers(name = FileMarker.ADDED), snapshot.markers(path("new.kt"), false))
        assertEquals(FileMarkers(name = FileMarker.MODIFIED), snapshot.markers(path("changed.kt"), false))
        assertEquals(FileMarkers(name = FileMarker.IGNORED), snapshot.markers(path("debug.log"), false))
        assertEquals(FileMarkers(name = FileMarker.IGNORED), snapshot.markers(path("build/app.jar"), false))
        assertEquals(FileMarkers.NONE, snapshot.markers(path("clean.kt"), false))
    }

    @Test
    fun `ignored folders get an orange name, other folders a bubble per status below them in order`() {
        val snapshot =
            snapshotOf(
                "1 .M N... 100644 100644 100644 e69de29 e69de29 src/main/Changed.kt",
                "1 A. N... 000000 100644 100644 0000000 e69de29 src/main/New.kt",
                "? src/test/scratch.kt",
                "! src/test/out.log",
                "! build/",
                "? drafts/",
                "! logs/today.log",
            )

        assertEquals(FileMarkers(name = FileMarker.IGNORED), snapshot.markers(path("build"), true))
        assertEquals(FileMarkers(name = FileMarker.IGNORED), snapshot.markers(path("build/libs"), true))
        assertEquals(FileMarkers(bubbles = listOf(FileMarker.UNVERSIONED)), snapshot.markers(path("drafts"), true))
        assertEquals(
            FileMarkers(bubbles = listOf(FileMarker.UNVERSIONED, FileMarker.ADDED, FileMarker.MODIFIED)),
            snapshot.markers(path("src"), true),
        )
        assertEquals(
            FileMarkers(bubbles = listOf(FileMarker.ADDED, FileMarker.MODIFIED)),
            snapshot.markers(path("src/main"), true),
        )
        // Ignored files do not mark their folder.
        assertEquals(FileMarkers(bubbles = listOf(FileMarker.UNVERSIONED)), snapshot.markers(path("src/test"), true))
        assertEquals(FileMarkers.NONE, snapshot.markers(path("logs"), true))
        assertEquals(FileMarkers.NONE, snapshot.markers(path("docs"), true))
    }

    @Test
    fun `without a repository nothing is marked`() {
        assertEquals(FileMarkers.NONE, GitStatusSnapshot.EMPTY.markers(path("a.kt"), false))
        assertEquals(FileMarkers.NONE, GitStatusSnapshot.EMPTY.markers(root, true))
    }
}
