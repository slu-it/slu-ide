package dev.sluide.ide.git

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.fileexplorer.FileMarker
import dev.sluide.fileexplorer.FileMarkers
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.git.GitStatusService
import dev.sluide.git.GitStatusSnapshot
import dev.sluide.git.cli.GitCli
import dev.sluide.ide.trustingTrust
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        val service = GitStatusService(git, watcher, trustingTrust(), BackgroundTasks.forTests()) { dispatched += it }
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

    @Test
    fun `git ignores a folder only as a whole inside the repository`() {
        val snapshot = snapshotOf("! node_modules/", "? drafts/", "! tools/out.log")

        assertEquals(true, snapshot.ignoresFolder(path("node_modules")))
        assertEquals(true, snapshot.ignoresFolder(path("node_modules/pkg")))
        assertEquals(false, snapshot.ignoresFolder(path("drafts")))
        assertEquals(false, snapshot.ignoresFolder(path("tools/build")))
        assertEquals(false, snapshot.ignoresFolder(path("tools")), "ignored files do not make the folder ignored")
        assertNull(snapshot.ignoresFolder(root.resolveSibling("elsewhere")))
        assertNull(GitStatusSnapshot.EMPTY.ignoresFolder(path("node_modules")))
    }

    /** Creates [relative] below [root] with the modification time [age] before [startedAt] (negative: after). */
    private fun file(
        relative: String,
        startedAt: Instant,
        age: Duration,
    ): Path =
        path(relative).also {
            it.parent.createDirectories()
            it.writeText("")
            Files.setLastModifiedTime(it, FileTime.from(startedAt.minus(age)))
        }

    @Test
    fun `git tracks a file unless it is untracked or ignored, also inside such folders`() {
        val snapshot =
            snapshotOf(
                "1 .M N... 100644 100644 100644 e69de29 e69de29 modified/.DS_Store",
                "1 A. N... 000000 100644 100644 0000000 e69de29 added/.DS_Store",
                "? untracked/.DS_Store",
                "? newdir/",
                "! ignored/.DS_Store",
                "! outdir/",
            )
        val started = snapshot.startedAt ?: fail("no start time")
        val old = Duration.ofHours(1)

        assertEquals(
            true,
            snapshot.tracksFile(file("clean/.DS_Store", started, old)),
            "no entry means tracked and clean",
        )
        assertEquals(true, snapshot.tracksFile(file("modified/.DS_Store", started, old)))
        assertEquals(true, snapshot.tracksFile(file("added/.DS_Store", started, old)))
        assertEquals(false, snapshot.tracksFile(path("untracked/.DS_Store")))
        assertEquals(false, snapshot.tracksFile(path("ignored/.DS_Store")))
        assertEquals(false, snapshot.tracksFile(path("newdir/deep/.DS_Store")))
        assertEquals(false, snapshot.tracksFile(path("outdir/deep/.DS_Store")))
        assertNull(snapshot.tracksFile(root.resolveSibling("elsewhere/.DS_Store")))
        assertNull(GitStatusSnapshot.EMPTY.tracksFile(path(".DS_Store")))
    }

    @Test
    fun `a file the status cannot know yet is not tracked as far as git says`() {
        val snapshot = snapshotOf("? other.txt")
        val started = snapshot.startedAt ?: fail("no start time")

        assertEquals(true, snapshot.tracksFile(file("old/.DS_Store", started, Duration.ofSeconds(3))))
        assertNull(snapshot.tracksFile(file("margin/.DS_Store", started, Duration.ofSeconds(1))), "within the margin")
        assertNull(snapshot.tracksFile(file("new/.DS_Store", started, Duration.ofMinutes(-1))), "after the run began")
        assertNull(snapshot.tracksFile(path("missing/.DS_Store")), "no modification time counts as new")
    }

    @Test
    fun `git has no say about a file inside a nested repository`() {
        val snapshot = snapshotOf("? other.txt")
        val started = snapshot.startedAt ?: fail("no start time")
        val old = Duration.ofHours(1)
        val inSubmodule = file("sub/deep/.DS_Store", started, old)
        path("sub/.git").writeText("gitdir: ../.git/modules/sub")

        assertNull(snapshot.tracksFile(inSubmodule))
        assertEquals(true, snapshot.tracksFile(file("plain/.DS_Store", started, old)))
    }
}
