package dev.sluide.git.ssh

import dev.sluide.secrets.Encrypted
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [SshPassphrases] does when a git process ended: remembering, saving and adding to the agent, off the calling
 * thread. The answering rules are in [SshPassphrasesTest]; the fixture is [SshPassphrasesFixture].
 */
internal class SshPassphrasesCommitTest : SshPassphrasesFixture() {
    @Test
    fun `a successful process with Save checked stores the encrypted value under the key's context`() {
        val ui = FakeUi(reply("p w", save = true))
        answer(ui)
        ended(ui)

        assertEquals(
            mapper.readTree("""{"sshPassphrases":[{"key":"$key","value":"v1:p w|$context"}]}"""),
            stored(),
        )
        assertTrue(ui.notSaved.isEmpty())
        assertEquals(listOf(3), zeroed.map { it.size }, "the commit's own copy is zeroed")
    }

    @Test
    fun `without Save nothing is stored and nothing is started`() {
        val ui = FakeUi(reply(save = false))
        answer(ui)
        ended(ui)

        assertNull(stored())
        assertEquals(0, encryptions)
        assertTrue(jobs.isEmpty())
    }

    @Test
    fun `a disabled Save box saves nothing even if the reply says so`() {
        saveUnavailable = "needs secret-tool"
        val ui = FakeUi(reply(save = true))
        answer(ui)
        ended(ui)

        assertNull(stored())
        assertEquals(emptyList(), ui.notSaved)
    }

    @Test
    fun `Save acts once, also for the same answer reused by the session`() {
        val ui = FakeUi(reply(save = true))
        answer(ui)
        ended(ui)
        saved.remove(key)

        answer(ui, connection = 11)
        ended(ui)

        assertNull(stored())
    }

    @Test
    fun `an answer that comes from memory is never saved`() {
        val ui = FakeUi(reply(save = true))
        answer(ui)
        ended(ui)
        saved.remove(key)
        passphrases.closed(first)

        assertEquals("entered", answer(ui, session = second))
        ended(ui, session = second)

        assertEquals(1, ui.passphraseRequests.size, "from memory, not from the UI")
        assertNull(stored())
    }

    @Test
    fun `a saved answer is not stored again`() {
        saved.save(key, "from-saved".toCharArray())
        encryptions = 0
        val ui = FakeUi(reply())
        val before = stored()
        answer(ui)
        ended(ui)

        assertEquals(before, stored())
        assertEquals(0, encryptions, "a saved answer is not encrypted again")
    }

    @Test
    fun `an encryption that fails tells the UI why`() {
        encryptFailure = Encrypted.Failed("Keychain locked")
        val ui = FakeUi(reply(save = true))
        answer(ui)
        ended(ui)

        assertEquals(listOf("Keychain locked"), ui.notSaved)
        assertNull(stored())
    }

    @Test
    fun `a shared file that cannot be written tells the UI why`() {
        folder.resolve("shared.json").writeText("{ not json")
        val ui = FakeUi(reply(save = true))
        answer(ui)
        ended(ui)

        assertEquals(listOf("~/.sluide/shared.json cannot be written"), ui.notSaved)
    }

    @Test
    fun `ended does not wait for a slow encryption, and memory already has the answer`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        beforeEncrypt = {
            entered.countDown()
            release.await(20, TimeUnit.SECONDS)
        }
        val ui = FakeUi(reply(save = true))
        answer(ui)

        passphrases.ended(first, true, ui)

        assertTrue(entered.await(20, TimeUnit.SECONDS), "the encryption started")
        assertNull(stored(), "not written yet: ended returned while it blocks")
        assertEquals("entered", answer(ui, session = second, connection = 20), "memory first")
        assertEquals(1, ui.passphraseRequests.size)
        release.countDown()
        awaitJobs()
        assertEquals(1, stored()?.get("sshPassphrases")?.size())
    }

    @Test
    fun `Add to agent runs ssh-add for the key with the passphrase after the process succeeded`() {
        sshAdd = fakeSshAdd("exit 0")
        val ui = FakeUi(reply("agent pass", agent = true))
        answer(ui)
        assertFalse(folder.resolve("added-key").exists(), "not before the process ended")

        ended(ui)

        assertEquals("$key", folder.resolve("added-key").readText().trim())
        assertEquals("agent pass", folder.resolve("added-passphrase").readText().trim())
        assertEquals(emptyList(), ui.notAdded)
    }

    @Test
    fun `a failed process adds nothing to the agent`() {
        sshAdd = fakeSshAdd("exit 0")
        val ui = FakeUi(reply(agent = true))
        answer(ui)

        ended(ui, success = false)

        assertFalse(folder.resolve("added-key").exists())
    }

    @Test
    fun `a disabled agent box adds nothing even if the reply says so`() {
        environment = emptyMap()
        sshAdd = fakeSshAdd("exit 0")
        val ui = FakeUi(reply(agent = true))
        answer(ui)

        ended(ui)

        assertFalse(folder.resolve("added-key").exists())
    }

    @Test
    fun `Add to agent acts once`() {
        sshAdd = fakeSshAdd("exit 0")
        val ui = FakeUi(reply(agent = true))
        answer(ui)
        ended(ui)
        folder.resolve("added-key").toFile().delete()

        answer(ui, connection = 11)
        ended(ui)

        assertFalse(folder.resolve("added-key").exists())
    }

    @Test
    fun `a key that ssh-add rejects is reported to the UI`() {
        sshAdd = fakeSshAdd("echo 'Bad passphrase' >&2; exit 1")
        val ui = FakeUi(reply(agent = true))
        answer(ui)

        ended(ui)

        assertEquals(listOf("Bad passphrase"), ui.notAdded)
    }

    @Test
    fun `ended does not wait for a slow ssh-add`() {
        val release = folder.resolve("release")
        sshAdd = fakeSshAdd("while [ ! -e '$release' ]; do sleep 0.05; done")
        val ui = FakeUi(reply(agent = true))
        answer(ui)

        passphrases.ended(first, true, ui)

        // ended returned while ssh-add still waits for the release file, which only this test creates.
        release.writeText("go")
        awaitJobs()
        assertEquals(emptyList(), ui.notAdded)
    }

    @Test
    fun `a failed process then a successful one in the same session commits the answer`() {
        val ui = FakeUi(reply("kept", save = true))
        answer(ui, connection = 10)
        ended(ui, success = false)
        assertNull(stored())

        answer(ui, connection = 11)
        ended(ui, success = true)

        assertEquals(1, stored()?.get("sshPassphrases")?.size())
        assertEquals(1, ui.passphraseRequests.size)
    }

    @Test
    fun `an answer no process used is not committed by a later success`() {
        val ui = FakeUi(reply("unused"))
        answer(ui)
        ended(ui, success = false)

        ended(ui, success = true)

        passphrases.closed(first)
        assertEquals("entered", answer(FakeUi(reply("entered")), session = second))
    }

    @Test
    fun `an answer that comes after its process ended is dropped, not committed by the next process`() {
        // The process was stopped (FETCH_TIMEOUT) while the dialog was open, and the user answered all the same.
        lateinit var ui: FakeUi
        ui =
            FakeUi(passphrase = {
                passphrases.ended(first, false, ui)
                PassphraseReply("late".toCharArray(), save = true, addToAgent = false)
            })

        // An earlier process of the session ended first, so that the late one is not the session's first.
        openedFor(first)
        ended(ui, success = false)

        assertNull(answer(ui))
        ended(ui, success = true)

        assertNull(stored(), "not saved")
        assertTrue(zeroed.any { it.size == 4 && it.all { c -> c == '\u0000' } }, "the late answer is zeroed")
        // The process after it asks again and gets its answer, which its success commits.
        val again = FakeUi(reply("again", save = true))
        assertEquals("again", answer(again, connection = 11))
        ended(again, success = true)
        assertEquals(1, stored()?.get("sshPassphrases")?.size())
        passphrases.closed(first)
        assertEquals("again", answer(FakeUi(reply("entered")), session = second), "only the good one is remembered")
    }

    @Test
    fun `saving does not run ssh-add, and adding to the agent does not save`() {
        sshAdd = fakeSshAdd("exit 0")
        val saveOnly = FakeUi(reply(save = true))
        answer(saveOnly)
        ended(saveOnly)
        assertFalse(folder.resolve("added-key").exists())
        assertEquals(1, encryptions)

        val agentOnly = FakeUi(reply(agent = true))
        answer(agentOnly, session = second)
        answer(agentOnly, session = second)
        ended(agentOnly, session = second)
        assertTrue(folder.resolve("added-key").exists())
        assertEquals(1, encryptions, "no encryption for the agent only")
    }

    @Test
    fun `the bean's own constructor reads git's environment and commits on the named virtual thread`() {
        val real = SshPassphrases(channel, saved)
        real.opened(first)
        var thread: Thread? = null
        beforeEncrypt = { thread = Thread.currentThread() }
        val ui = FakeUi(reply(save = true))
        real.answer(first, 10, key, ui)
        val socket = System.getenv("SSH_AUTH_SOCK")
        val expected = if (socket.isNullOrEmpty()) "no ssh agent" else null
        assertEquals(expected, ui.passphraseRequests.single().agentUnavailable)

        real.ended(first, true, ui)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (stored() == null && System.nanoTime() < deadline) Thread.sleep(20) // the commit is on its own thread

        assertTrue(thread?.isVirtual == true)
        assertEquals("sluide-git-ssh-passphrase", thread?.name)
        real.destroy()
    }
}
