package dev.sluide.gitlog

import dev.sluide.git.GitConflictReason
import dev.sluide.git.GitOperation
import dev.sluide.git.GitPendingOperation
import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitPick
import dev.sluide.git.GitPickResult
import dev.sluide.git.GitResetMode
import dev.sluide.git.GitResult
import dev.sluide.git.GitUncheckedConflict
import dev.sluide.gitlog.data.FakeWrites
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommitOperationsTest {
    private val root = Path.of("/repo")

    private val hash = "1a2b3c4d5e6f"

    private val writes = FakeWrites()

    @Test
    fun `a cherry-pick and a revert say what they do and did`() {
        val pick = PickCommit(GitPick.CHERRY_PICK, hash)
        assertEquals("Cherry-picking 1a2b3c4d…", pick.progress)
        assertEquals("Cherry-Pick Failed", pick.errorTitle)
        assertEquals("1a2b3c4d could not be cherry-picked.", pick.failure)
        assertEquals("Cherry-Pick", pick.fileChange)
        assertEquals(RefOutcome(GitResult(true, ""), "Cherry-picked 1a2b3c4d as 5e6f7a8b."), pick.run(writes, root))

        val revert = PickCommit(GitPick.REVERT, hash, firstParent = true)
        assertEquals("Reverting 1a2b3c4d…", revert.progress)
        assertEquals("Revert Failed", revert.errorTitle)
        assertEquals("1a2b3c4d could not be reverted.", revert.failure)
        assertEquals("Revert", revert.fileChange)
        assertEquals("Reverted 1a2b3c4d in 5e6f7a8b.", revert.run(writes, root).done)

        writes.pickResult = { GitPickResult.Done(null) }
        assertEquals("Cherry-picked 1a2b3c4d.", pick.run(writes, root).done)
        assertEquals(listOf("cherry-pick $hash", "revert -m 1 $hash", "cherry-pick $hash"), writes.calls)
    }

    @Test
    fun `a cherry-pick or revert that would overwrite ignored files asks first, listing them`() {
        writes.pickOverwritten = listOf(".env", "build/cache/data")
        val pick = PickCommit(GitPick.CHERRY_PICK, hash)

        val asked = pick.run(writes, root)

        assertEquals(
            RefConfirmation(
                "Cherry-Pick",
                "2 ignored files will be overwritten or deleted by the cherry-pick of 1a2b3c4d; git keeps no copy of them:",
                CHERRY_PICK_BUTTON,
                pick.copy(confirmed = true, shown = listOf(".env", "build/cache/data")),
                ".env\nbuild/cache/data",
            ),
            asked.confirmation,
        )
        assertEquals(emptyList(), writes.calls, "nothing done before the answer")

        // Read again once confirmed: fewer than shown is fine.
        writes.pickOverwritten = listOf(".env")
        val confirmed = assertNotNull(asked.confirmation).confirmed
        assertEquals("Cherry-picked 1a2b3c4d as 5e6f7a8b.", confirmed.run(writes, root).done)
        assertEquals(listOf("cherry-pick $hash"), writes.calls)
        assertEquals(2, writes.reads.count { it == "pick overwrites $hash" })

        writes.pickOverwritten = listOf(".env")
        assertEquals(
            "1 ignored file will be overwritten or deleted by the revert of 1a2b3c4d; git keeps no copy of it:",
            PickCommit(GitPick.REVERT, hash).run(writes, root).confirmation?.question,
        )
    }

    @Test
    fun `a cherry-pick whose overwritten files cannot be read asks first too`() {
        writes.pickOverwritten = null

        val confirmation = PickCommit(GitPick.CHERRY_PICK, hash).run(writes, root).confirmation

        assertEquals(
            "Whether the cherry-pick of 1a2b3c4d overwrites or deletes ignored files could not be read.\n" +
                "Cherry-pick 1a2b3c4d anyway?",
            confirmation?.question,
        )
        assertNull(confirmation?.details)
        assertEquals(emptyList(), writes.calls)
    }

    @Test
    fun `files in the way that were not shown when it was confirmed are asked about again`() {
        writes.pickOverwritten = listOf(".env")
        val asked = assertNotNull(PickCommit(GitPick.CHERRY_PICK, hash).run(writes, root).confirmation)

        // A build recreated its folder while the dialog was open.
        writes.pickOverwritten = listOf(".env", "build/cache/data")
        val again = assertNotNull(asked.confirmed.run(writes, root).confirmation)

        assertEquals(".env\nbuild/cache/data", again.details)
        assertEquals(emptyList(), writes.calls)

        // One that could not be read is asked about again once it can, with what was found.
        writes.pickOverwritten = null
        val unread = assertNotNull(PickCommit(GitPick.REVERT, hash).run(writes, root).confirmation)
        writes.pickOverwritten = listOf(".env")
        assertEquals(
            ".env",
            unread.confirmed
                .run(writes, root)
                .confirmation
                ?.details,
        )
        writes.pickOverwritten = null
        assertNull(unread.confirmed.run(writes, root).confirmation, "still unreadable, as confirmed")
    }

    @Test
    fun `while git is in the middle of something nothing is read first, the pick refuses`() {
        writes.inProgress = GitPendingOperation(GitOperation.MERGE)
        writes.pickOverwritten = listOf(".env")

        assertNull(PickCommit(GitPick.CHERRY_PICK, hash).run(writes, root).confirmation)
        assertTrue(writes.reads.none { it.startsWith("pick overwrites") })
    }

    @Test
    fun `nothing to commit is no failure`() {
        writes.pickResult = { GitPickResult.Empty("main") }
        assertEquals(
            RefOutcome(GitResult(true, ""), "Nothing to cherry-pick: the changes of 1a2b3c4d are already on main."),
            PickCommit(GitPick.CHERRY_PICK, hash).run(writes, root),
        )
        writes.pickResult = { GitPickResult.Empty(null) }
        assertEquals(
            "Nothing to cherry-pick: the changes of 1a2b3c4d are already on HEAD.",
            PickCommit(GitPick.CHERRY_PICK, hash).run(writes, root).done,
        )
        assertEquals(
            "Nothing to revert: the changes of 1a2b3c4d are already undone.",
            PickCommit(GitPick.REVERT, hash).run(writes, root).done,
        )
    }

    @Test
    fun `a stop is handed back for the user, a failure and markers left are errors`() {
        writes.pickResult = { GitPickResult.Stopped(listOf("a.txt", "b/c.txt"), "error: could not apply") }
        val stopped = PickCommit(GitPick.REVERT, hash).run(writes, root)
        assertEquals("Revert of 1a2b3c4d stopped with conflicts.", stopped.done)
        assertEquals(
            PickStop(GitPick.REVERT, hash, listOf("a.txt", "b/c.txt"), "error: could not apply"),
            stopped.stopped,
        )
        assertEquals(true, stopped.result.success, "no error dialog")

        writes.pickResult = { GitPickResult.Stopped(emptyList(), "hook failed") }
        assertEquals("Cherry-pick of 1a2b3c4d stopped.", PickCommit(GitPick.CHERRY_PICK, hash).run(writes, root).done)

        writes.pickResult = { GitPickResult.Failed("error: local changes") }
        assertEquals(
            RefOutcome(GitResult(false, "error: local changes"), null),
            PickCommit(GitPick.CHERRY_PICK, hash).run(writes, root),
        )

        writes.pickResult = { GitPickResult.MarkersLeft(listOf("a.txt", "b/c.txt")) }
        val markers = ContinuePick(GitPick.CHERRY_PICK, hash).run(writes, root)
        assertEquals(RefOutcome(GitResult(false, "Conflict markers are left in: a.txt, b/c.txt"), null), markers)
    }

    @Test
    fun `continue and abort say what they do and did`() {
        val proceed = ContinuePick(GitPick.CHERRY_PICK, hash)
        assertEquals("Continuing the cherry-pick of 1a2b3c4d…", proceed.progress)
        assertEquals("Continue Failed", proceed.errorTitle)
        assertEquals("The cherry-pick of 1a2b3c4d could not be continued.", proceed.failure)
        assertEquals("Continue", proceed.fileChange)
        assertEquals("Cherry-picked 1a2b3c4d as 5e6f7a8b.", proceed.run(writes, root).done)

        val abort = AbortPick(GitPick.REVERT, hash)
        assertEquals("Aborting the revert of 1a2b3c4d…", abort.progress)
        assertEquals("Abort Failed", abort.errorTitle)
        assertEquals("The revert of 1a2b3c4d could not be aborted.", abort.failure)
        assertEquals("Abort", abort.fileChange)
        assertEquals(RefOutcome(GitResult(true, ""), "Aborted the revert of 1a2b3c4d."), abort.run(writes, root))
        writes.result = { GitResult(false, "error: no revert in progress") }
        assertEquals(RefOutcome(GitResult(false, "error: no revert in progress"), null), abort.run(writes, root))

        assertEquals(listOf("cherry-pick --continue", "revert --abort", "revert --abort"), writes.calls)
    }

    @Test
    fun `a reset without anything to lose runs at once and says where the branch was`() {
        val reset = ResetBranch(hash, GitResetMode.HARD, "main")
        assertEquals("Resetting main to 1a2b3c4d…", reset.progress)
        assertEquals("Reset Failed", reset.errorTitle)
        assertEquals("main could not be reset to 1a2b3c4d.", reset.failure)
        assertEquals("Reset", reset.fileChange)
        assertNull(ResetBranch(hash, GitResetMode.SOFT, "main").fileChange, "the files stay")
        assertNull(ResetBranch(hash, GitResetMode.MIXED, "main").fileChange)
        writes.head = "9f8e7d6c5b4a"

        assertEquals(
            RefOutcome(GitResult(true, ""), "Reset main to 1a2b3c4d (hard); it was at 9f8e7d6c."),
            reset.run(writes, root),
        )
        assertEquals(
            "Reset HEAD to 1a2b3c4d (soft); it was at 9f8e7d6c.",
            ResetBranch(hash, GitResetMode.SOFT, null).run(writes, root).done,
        )
        assertEquals(listOf("reset --hard $hash", "reset --soft $hash"), writes.calls)
        assertEquals(
            listOf("unreachable $hash", "local changes", "overwritten $hash", "soft refused", "unreachable $hash"),
            writes.reads,
            "only a hard reset reads local changes and overwritten files",
        )
    }

    @Test
    fun `a reset that loses commits or local changes asks first`() {
        writes.unreachable = 2
        writes.localChanges = 3
        val hard = ResetBranch(hash, GitResetMode.HARD, "main")

        val asked = hard.run(writes, root)

        assertEquals(
            RefConfirmation(
                "Reset Current Branch",
                "2 commits will no longer be on any branch or tag; they stay reachable through the reflog for a " +
                    "while.\nLocal changes in 3 files will be lost.",
                RESET_BUTTON,
                hard.copy(confirmed = true),
            ),
            asked.confirmation,
        )
        assertNull(asked.done)
        assertEquals(emptyList(), writes.calls, "nothing done")
        assertEquals("Reset HEAD", ResetBranch(hash, GitResetMode.MIXED, null).run(writes, root).confirmation?.title)
        assertEquals("Reset main to 1a2b3c4d (hard); it was at h1.", hard.copy(confirmed = true).run(writes, root).done)
        assertEquals(listOf("reset --hard $hash"), writes.calls)

        writes.unreachable = 0
        assertNull(ResetBranch(hash, GitResetMode.MIXED, "main").run(writes, root).confirmation, "local changes stay")
    }

    @Test
    fun `what a reset would lose is told in a sentence each`() {
        assertEquals(emptyList(), resetWarnings(0, 0))
        assertEquals(
            listOf(
                "1 commit will no longer be on any branch or tag; it stays reachable through the reflog for a while.",
                "Local changes in 1 file will be lost.",
            ),
            resetWarnings(1, 1),
        )
        assertEquals(
            listOf(
                "Whether commits will no longer be on any branch or tag could not be read.",
                "Whether local changes will be lost could not be read.",
            ),
            resetWarnings(null, null),
        )
        assertEquals(
            listOf(
                "Staged changes in 1 file that differ from the file on disk will be lost.",
                "1 untracked or ignored file will be overwritten by the commit's version:",
            ),
            resetWarnings(0, 0, 1, listOf(".env")),
        )
        assertEquals(
            listOf(
                "Whether staged changes will be lost could not be read.",
                "Whether untracked or ignored files will be overwritten could not be read.",
            ),
            resetWarnings(0, 0, null, null),
        )
    }

    @Test
    fun `a hard reset that overwrites untracked or ignored files asks first and lists them`() {
        writes.overwritten = listOf(".env", "build/out.txt")
        val hard = ResetBranch(hash, GitResetMode.HARD, "main")

        val asked = hard.run(writes, root).confirmation

        assertEquals(
            RefConfirmation(
                "Reset Current Branch",
                "2 untracked or ignored files will be overwritten by the commit's version:",
                RESET_BUTTON,
                hard.copy(confirmed = true),
                ".env\nbuild/out.txt",
            ),
            asked,
        )
        assertEquals(emptyList(), writes.calls, "nothing reset before it is confirmed")
        assertNull(ResetBranch(hash, GitResetMode.MIXED, "main").run(writes, root).confirmation, "files stay")
        assertEquals(listOf("reset --mixed $hash"), writes.calls)
    }

    @Test
    fun `a mixed reset that loses staged changes asks first`() {
        writes.stagedOnly = 2

        assertEquals(
            "Staged changes in 2 files that differ from the files on disk will be lost.",
            ResetBranch(hash, GitResetMode.MIXED, "main").run(writes, root).confirmation?.question,
        )
        assertNull(ResetBranch(hash, GitResetMode.SOFT, "main").run(writes, root).confirmation, "the index stays")
        assertEquals(listOf("unreachable $hash", "staged only", "soft refused", "unreachable $hash"), writes.reads)
    }

    @Test
    fun `a reset while git is in the middle of something says what it does to it`() {
        writes.inProgress = GitPendingPick(GitPick.CHERRY_PICK, hash, listOf("a.txt"))
        writes.unreachable = 1

        assertEquals(
            "A cherry-pick of 1a2b3c4d is in progress; resetting abandons it.\n1 commit will no longer be on any " +
                "branch or tag; it stays reachable through the reflog for a while.",
            ResetBranch(hash, GitResetMode.SOFT, "main").run(writes, root).confirmation?.question,
        )
        writes.unreachable = 0
        writes.inProgress = GitPendingOperation(GitOperation.REBASE)
        assertEquals(
            "A rebase is in progress; resetting drops the changes of the commit it stopped at, the rest stays to be " +
                "continued from the commit HEAD is reset to.",
            ResetBranch(hash, GitResetMode.MIXED, "main").run(writes, root).confirmation?.question,
        )
        assertEquals(
            "A rebase is in progress; the changes of the commit it stopped at stay staged, the rest stays to be " +
                "continued from the commit HEAD is reset to.",
            ResetBranch(hash, GitResetMode.SOFT, "main").run(writes, root).confirmation?.question,
        )
        assertEquals(
            listOf(
                "A revert of 1a2b3c4d is in progress; resetting abandons it.",
                "A merge is in progress; resetting abandons it.",
                "A git am session is in progress; resetting drops the changes of the patch it stopped at, the rest " +
                    "stays to be continued from the commit HEAD is reset to.",
                "A cherry-pick sequence is in progress; resetting drops the changes of the commit it stopped at, the " +
                    "rest stays to be continued from the commit HEAD is reset to.",
                "A revert sequence is in progress; resetting drops the changes of the commit it stopped at, the rest " +
                    "stays to be continued from the commit HEAD is reset to.",
                "A cherry-pick or revert sequence is in progress; resetting drops the changes of the commit it " +
                    "stopped at, the rest stays to be continued from the commit HEAD is reset to.",
            ),
            listOf(
                GitPendingPick(GitPick.REVERT, hash, emptyList()),
                GitPendingOperation(GitOperation.MERGE),
                GitPendingOperation(GitOperation.AM),
                GitPendingOperation(GitOperation.CHERRY_PICK_SEQUENCE),
                GitPendingOperation(GitOperation.REVERT_SEQUENCE),
                GitPendingOperation(GitOperation.SEQUENCE),
            ).map { busyResetWarning(it, GitResetMode.HARD) },
        )
        assertEquals(emptyList(), writes.calls)
    }

    @Test
    fun `a soft reset git would refuse fails at once, without asking`() {
        writes.softRefused = true
        writes.unreachable = 2

        assertEquals(
            RefOutcome(GitResult(false, SOFT_REFUSED), null),
            ResetBranch(hash, GitResetMode.SOFT, "main").run(writes, root),
        )
        assertEquals(listOf("soft refused"), writes.reads)
        assertEquals(emptyList(), writes.calls)
        assertEquals(
            "A soft reset is not possible during a merge or while files have conflicts; choose Mixed or Hard.",
            SOFT_REFUSED,
        )

        writes.softRefused = null
        assertEquals(
            RESET_BUTTON,
            ResetBranch(hash, GitResetMode.SOFT, "main").run(writes, root).confirmation?.button,
            "git tells",
        )
        writes.softRefused = true
        assertEquals(RESET_BUTTON, ResetBranch(hash, GitResetMode.MIXED, "main").run(writes, root).confirmation?.button)
    }

    @Test
    fun `aborting from the bar asks what is lost`() {
        assertEquals(
            "Abort the cherry-pick of 1a2b3c4d? Its changes are undone, also what was done to resolve its conflicts.",
            abortQuestion(GitPick.CHERRY_PICK, hash),
        )
        assertEquals(
            "Abort the revert of 1a2b3c4d? Its changes are undone, also what was done to resolve its conflicts.",
            abortQuestion(GitPick.REVERT, hash),
        )
    }

    @Test
    fun `texts of the dialogs and the bar`() {
        assertEquals("Reset branch 'main' (at 9f8e7d6c) to 1a2b3c4d", resetDescription("main", "9f8e7d6c5b4a", hash))
        assertEquals("Reset detached HEAD (at 9f8e7d6c) to 1a2b3c4d", resetDescription(null, "9f8e7d6c5b4a", hash))
        assertEquals("Reset Current Branch", resetTitle("main"))
        assertEquals("Reset HEAD", resetTitle(null))
        assertEquals(
            "1a2b3c4d is a merge commit. Cherry-pick the changes it brought in relative to its first parent?",
            mergeQuestion(GitPick.CHERRY_PICK, hash),
        )
        assertEquals(
            "1a2b3c4d is a merge commit. Revert the changes it brought in relative to its first parent?",
            mergeQuestion(GitPick.REVERT, hash),
        )
        assertEquals(
            "Cherry-pick of 1a2b3c4d is in progress: 2 files have conflicts.",
            pendingText(GitPendingPick(GitPick.CHERRY_PICK, hash, listOf("a", "b"))),
        )
        assertEquals(
            "Revert of 1a2b3c4d is in progress: 1 file has conflicts.",
            pendingText(GitPendingPick(GitPick.REVERT, hash, listOf("a"))),
        )
        assertEquals(
            "Cherry-pick of 1a2b3c4d is in progress: conflicts resolved.",
            pendingText(GitPendingPick(GitPick.CHERRY_PICK, hash, emptyList())),
        )
    }

    @Test
    fun `continue and abort use the commit in progress as read when they run`() {
        writes.inProgress = GitPendingPick(GitPick.CHERRY_PICK, "9f8e7d6c5b4a", emptyList())

        assertEquals(
            "Cherry-picked 9f8e7d6c as 5e6f7a8b.",
            ContinuePick(GitPick.CHERRY_PICK, hash).run(writes, root).done,
        )
        assertEquals(
            "Aborted the cherry-pick of 9f8e7d6c.",
            AbortPick(GitPick.CHERRY_PICK, hash).run(writes, root).done,
        )
        writes.inProgress = GitPendingOperation(GitOperation.MERGE)
        assertEquals("Aborted the revert of 1a2b3c4d.", AbortPick(GitPick.REVERT, hash).run(writes, root).done)
    }

    @Test
    fun `conflicted files that cannot be checked are committed as on disk once confirmed`() {
        val files =
            listOf(
                GitUncheckedConflict("bin.dat", GitConflictReason.BINARY),
                GitUncheckedConflict("m.txt", GitConflictReason.DELETED_ON_ONE_SIDE),
                GitUncheckedConflict("gone.txt", GitConflictReason.DELETED_ON_DISK),
                GitUncheckedConflict("sub", GitConflictReason.SUBMODULE),
            )
        writes.pickResult = { line ->
            if (line.endsWith("as on disk")) GitPickResult.Done(FakeWrites.NEW_HEAD) else GitPickResult.Unchecked(files)
        }
        val proceed = ContinuePick(GitPick.CHERRY_PICK, hash)

        val asked = proceed.run(writes, root)

        assertEquals(
            RefConfirmation(
                "Continue Cherry-Pick",
                "These conflicted files cannot be checked for conflict markers. Commit them as they are on disk?",
                COMMIT_AS_ON_DISK_BUTTON,
                proceed.copy(confirmed = files.toSet()),
                "bin.dat \u2014 binary, the version on disk will be committed\n" +
                    "m.txt \u2014 deleted on one side, the file on disk will be committed\n" +
                    "gone.txt \u2014 deleted on disk, the deletion will be committed\n" +
                    "sub \u2014 submodule, the commit checked out in it will be committed",
            ),
            asked.confirmation,
        )
        assertNull(asked.done)
        assertEquals(
            "Cherry-picked 1a2b3c4d as 5e6f7a8b.",
            asked.confirmation
                ?.confirmed
                ?.run(writes, root)
                ?.done,
        )
        assertEquals(listOf("cherry-pick --continue", "cherry-pick --continue as on disk"), writes.calls)
        assertEquals(listOf(emptySet(), files.toSet()), writes.confirmedSets, "exactly the files shown")
        assertEquals(
            "too large to check, the version on disk will be committed",
            reasonText(GitConflictReason.TOO_LARGE),
        )
        assertEquals("symbolic link, the link on disk will be committed", reasonText(GitConflictReason.SYMLINK))
    }

    @Test
    fun `the bar tells what else is in progress`() {
        assertEquals("A merge is in progress; finish or abort it in the terminal.", operationText(GitOperation.MERGE))
        assertEquals(
            "A cherry-pick sequence is in progress; continue or abort it in the terminal.",
            operationText(GitOperation.CHERRY_PICK_SEQUENCE),
        )
        assertEquals(
            "A rebase is in progress; continue or abort it in the terminal.",
            operationText(GitOperation.REBASE),
        )
        assertEquals(
            "A revert sequence is in progress; continue or abort it in the terminal.",
            operationText(GitOperation.REVERT_SEQUENCE),
        )
    }
}
