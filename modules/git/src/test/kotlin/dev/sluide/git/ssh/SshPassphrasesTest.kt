package dev.sluide.git.ssh

import dev.sluide.secrets.Decrypted
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How [SshPassphrases] answers ssh's prompts: from the session's pending answer, memory, the saved value or the UI, and
 * when ssh asking again means the answer was wrong. The fixture is [SshPassphrasesFixture]; what happens after a git
 * process ended is in [SshPassphrasesCommitTest].
 */
internal class SshPassphrasesTest : SshPassphrasesFixture() {
    @Test
    fun `memory is used without asking the UI`() {
        remember()
        val ui = FakeUi(reply())

        assertEquals("remembered", answer(ui))
        assertEquals(0, ui.calls)
    }

    @Test
    fun `a saved passphrase is used without asking the UI`() {
        saved.save(key, "from-saved".toCharArray())
        val ui = FakeUi(reply())

        assertEquals("from-saved", answer(ui))
        assertEquals(0, ui.calls)
    }

    @Test
    fun `a saved passphrase reaches memory only after the process succeeded`() {
        saved.save(key, "from-saved".toCharArray())
        val ui = FakeUi(reply("asked"))
        answer(ui)
        saved.remove(key)
        assertEquals("asked", answer(ui, session = second), "not in memory before the process ended")

        saved.save(key, "from-saved".toCharArray())
        answer(ui, session = SessionId(3))
        ended(ui, session = SessionId(3))
        saved.remove(key)
        assertEquals("from-saved", answer(ui, session = SessionId(4)), "in memory after it succeeded")
        assertEquals(1, ui.calls)
    }

    @Test
    fun `an unreadable saved value is removed, then the UI is asked without a notice`() {
        store.updateShared {
            it.set("git", mapper.readTree("""{"sshPassphrases":[{"key":"$key","value":"v1:x|elsewhere"}]}"""))
        }
        val ui = FakeUi(reply())

        assertEquals("entered", answer(ui))

        assertEquals(listOf(null), ui.passphraseRequests.map { it.notice })
        assertNull(stored())
    }

    @Test
    fun `a saved value without a key is kept and the UI is asked`() {
        saved.save(key, "from-saved".toCharArray())
        noKey = "locked"
        val ui = FakeUi(reply())

        assertEquals("entered", answer(ui))

        assertEquals(1, ui.passphraseRequests.size)
        assertEquals(1, stored()?.get("sshPassphrases")?.size())
    }

    @Test
    fun `asked again on the same connection after a memory answer drops memory and says wrong`() {
        remember()
        zeroed.clear()
        val ui = FakeUi(reply("second"))
        assertEquals("remembered", answer(ui))

        assertEquals("second", answer(ui))

        assertEquals(listOf(10, 10), zeroed.map { it.size }, "the memory's array and the pending one")

        assertEquals(listOf(PassphraseNotice.WRONG), ui.passphraseRequests.map { it.notice })
        passphrases.closed(first)
        val later = FakeUi(reply("third"))
        assertEquals("third", answer(later, session = second, connection = 20), "memory no longer has it")
        assertEquals(listOf(null), later.passphraseRequests.map { it.notice })
    }

    @Test
    fun `asked again after a saved answer removes the entry and says it was rejected`() {
        saved.save(key, "from-saved".toCharArray())
        val ui = FakeUi(reply("second"))
        assertEquals("from-saved", answer(ui))

        assertEquals("second", answer(ui))

        assertEquals(listOf(PassphraseNotice.SAVED_REJECTED), ui.passphraseRequests.map { it.notice })
        assertNull(saved.load(key))
        assertEquals(listOf(10, 10), zeroed.map { it.size }, "the pending answer and the copy for the removal")
    }

    @Test
    fun `a wrong answer from memory leaves an equal saved value alone`() {
        saved.save(key, "from-saved".toCharArray())
        val first = FakeUi(reply())
        answer(first)
        ended(first)
        passphrases.closed(this.first)
        val ui = FakeUi(reply("second"))
        assertEquals("from-saved", answer(ui, session = second))

        answer(ui, session = second)

        assertEquals(listOf(PassphraseNotice.WRONG), ui.passphraseRequests.map { it.notice })
        assertEquals("from-saved", String(assertIs<Decrypted.Plain>(saved.load(key)).value))
    }

    @Test
    fun `asked again without a pending answer asks the UI even if something was saved meanwhile`() {
        val replies = ArrayDeque(listOf<PassphraseReply?>(null, PassphraseReply("x".toCharArray(), false, false)))
        val ui = FakeUi(passphrase = { replies.removeFirst() })
        assertNull(answer(ui))
        saved.save(key, "saved-later".toCharArray())

        assertEquals("x", answer(ui))

        assertEquals(2, ui.passphraseRequests.size)
    }

    @Test
    fun `asked again after an entered answer says wrong`() {
        val ui = FakeUi(reply())
        answer(ui)

        answer(ui)

        assertEquals(listOf(null, PassphraseNotice.WRONG), ui.passphraseRequests.map { it.notice })
    }

    @Test
    fun `a third ask keeps saying wrong for the new answer`() {
        val ui = FakeUi(reply())
        answer(ui)
        answer(ui)
        answer(ui)

        assertEquals(
            listOf(null, PassphraseNotice.WRONG, PassphraseNotice.WRONG),
            ui.passphraseRequests.map { it.notice },
        )
    }

    @Test
    fun `another connection of the session reuses the pending answer without UI and without a notice`() {
        val ui = FakeUi(reply())
        answer(ui, connection = 10)

        assertEquals("entered", answer(ui, connection = 11))

        assertEquals(1, ui.passphraseRequests.size)
    }

    @Test
    fun `a failed process keeps nothing but its session may reuse the answer`() {
        val ui = FakeUi(reply("entered", save = true))
        answer(ui)
        ended(ui, success = false)

        assertNull(stored())
        assertEquals("entered", answer(ui, connection = 11), "the session's next process")
        assertEquals(1, ui.passphraseRequests.size)
        passphrases.closed(first)
        assertEquals("entered", answer(ui, session = second, connection = 12), "memory and saved hold nothing: asks")
        assertEquals(2, ui.passphraseRequests.size)
    }

    @Test
    fun `a retry after a failed process still counts the kept answer as wrong`() {
        val ui = FakeUi(reply())
        answer(ui, connection = 10)
        ended(ui, success = false)
        answer(ui, connection = 11)

        answer(ui, connection = 11)

        assertEquals(listOf(null, PassphraseNotice.WRONG), ui.passphraseRequests.map { it.notice })
    }

    @ParameterizedTest
    @MethodSource("agentCases")
    fun `the request says why the agent is unavailable`(
        agentEnvironment: Map<String, String>,
        kind: String,
        agent: String?,
    ) {
        environment = agentEnvironment
        val file =
            when (kind) {
                "file" -> key
                "folder" -> home
                else -> home.resolve("no-such-key")
            }
        val ui = FakeUi(reply())

        openedFor(first).answer(first, 10, file, ui)

        assertEquals(listOf(agent), ui.passphraseRequests.map { it.agentUnavailable })
    }

    @Test
    fun `the request carries the reason why saving is unavailable`() {
        saveUnavailable = "needs secret-tool"
        val ui = FakeUi(reply())

        openedFor(first).answer(first, 10, key, ui)

        assertEquals("needs secret-tool", ui.passphraseRequests.single().saveUnavailable)
    }

    @Test
    fun `a cancelled prompt gives no answer and keeps nothing`() {
        val ui = FakeUi(passphrase = { null })

        assertNull(answer(ui))
        ended(ui)
        passphrases.closed(first)

        assertNull(stored())
        assertEquals(emptyList(), zeroed)
    }

    @Test
    fun `an answer for a closed session is none and does not ask`() {
        val ui = FakeUi(reply())
        openedFor(first)
        answer(ui)
        assertEquals(1, passphrases.openSessions)
        passphrases.closed(first)
        assertEquals(1, ui.calls)

        assertNull(passphrases.answer(first, 11, key, ui))

        assertEquals(1, ui.calls, "no dialog for a closed session")
        assertEquals(0, passphrases.openSessions, "and no state is left behind")
    }

    @Test
    fun `an answer for a session that was never opened is none and does not ask`() {
        val ui = FakeUi(reply())

        assertNull(passphrases.answer(SessionId(50), 10, key, ui))

        assertEquals(0, ui.calls)
        assertEquals(0, passphrases.openSessions)
    }

    @Test
    fun `an answer after destroy is none, and sessions opened later work again`() {
        val ui = FakeUi(reply())
        openedFor(first)
        passphrases.destroy()

        assertNull(passphrases.answer(first, 10, key, ui))
        assertEquals(0, ui.calls)
        assertEquals("entered", answer(ui, session = second))
    }

    @Test
    fun `a session that ended while its passphrase was asked leaves no state`() {
        val asking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ui =
            FakeUi(
                passphrase = {
                    asking.countDown()
                    release.await(20, TimeUnit.SECONDS)
                    PassphraseReply("typed".toCharArray(), false, false)
                },
            )
        val thread = Thread.ofVirtual().start { openedFor(first).answer(first, 10, key, ui) }
        assertTrue(asking.await(20, TimeUnit.SECONDS))

        passphrases.closed(first)
        release.countDown()
        thread.join(20_000)

        assertEquals(0, passphrases.openSessions)
    }

    @Test
    fun `a saved answer that ssh rejects removes only that value, not a newer one`() {
        saved.save(key, "old".toCharArray())
        val ui = FakeUi(reply("typed"))
        assertEquals("old", answer(ui))
        saved.save(key, "newer".toCharArray())

        answer(ui)

        assertEquals(listOf(PassphraseNotice.SAVED_REJECTED), ui.passphraseRequests.map { it.notice })
        assertEquals("newer", String(assertIs<Decrypted.Plain>(saved.load(key)).value))
    }

    @Test
    fun `closing the session zeroes its pending answers`() {
        val ui = FakeUi(reply("entered"))
        answer(ui)

        passphrases.closed(first)

        assertEquals(1, zeroed.size)
        assertEquals(7, zeroed.single().size)
        assertTrue(zeroed.single().all { it == '\u0000' })
    }

    @Test
    fun `destroy zeroes the remembered passphrases`() {
        remember("remembered")
        assertEquals(1, zeroed.size, "the closed session's")

        passphrases.destroy()

        assertEquals(2, zeroed.size)
        assertEquals(10, zeroed.last().size)
        assertTrue(zeroed.last().all { it == '\u0000' })
    }

    @Test
    fun `the reply's array is zeroed and the answer is a copy`() {
        val array = "entered".toCharArray()
        val ui = FakeUi(passphrase = { PassphraseReply(array, save = false, addToAgent = false) })

        val result = openedFor(first).answer(first, 10, key, ui)

        assertTrue(array.all { it == '\u0000' })
        assertEquals("entered", String(result ?: CharArray(0)))
        result?.fill('x')
        assertEquals("entered", answer(ui, connection = 11), "the pending answer is not the returned array")
    }

    @Test
    fun `memory replaces an older value and zeroes it`() {
        val longer = FakeUi(reply("aaa"))
        val shorter = FakeUi(reply("b"))
        answer(longer, session = first)
        answer(shorter, session = second)
        ended(longer, session = first)
        assertEquals(emptyList(), zeroed, "nothing replaced yet")

        ended(shorter, session = second)

        assertEquals(3, zeroed.single().size)
        assertTrue(zeroed.single().all { it == '\u0000' })
        assertEquals("b", answer(FakeUi(reply()), session = SessionId(5)))
    }

    @Test
    fun `a saved value that stays because there is no key is not removed by a wrong entered answer`() {
        saved.save(key, "from-saved".toCharArray())
        noKey = "locked"
        val ui = FakeUi(reply())
        answer(ui)

        answer(ui)

        assertEquals(listOf(null, PassphraseNotice.WRONG), ui.passphraseRequests.map { it.notice })
        assertEquals(1, stored()?.get("sshPassphrases")?.size())
    }

    @Test
    fun `asked again after a cancel gives no notice`() {
        val replies = ArrayDeque(listOf<PassphraseReply?>(null, PassphraseReply("x".toCharArray(), false, false)))
        val ui = FakeUi(passphrase = { replies.removeFirst() })
        assertNull(answer(ui))

        assertEquals("x", answer(ui))

        assertEquals(listOf(null, null), ui.passphraseRequests.map { it.notice })
    }

    @Test
    fun `a newer remembered value is not dropped when an older answer turns out wrong`() {
        val newer = FakeUi(reply("newer-value"))
        answer(newer, session = SessionId(3), connection = 30)
        remember("old")
        val ui = FakeUi(reply("again"))
        assertEquals("old", answer(ui))
        ended(newer, session = SessionId(3))

        answer(ui)

        assertEquals("newer-value", answer(FakeUi(reply()), session = SessionId(4), connection = 40))
    }

    @Test
    fun `a session closed while the UI waits gets no answer and keeps nothing`() {
        val asking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ui =
            FakeUi(
                passphrase = {
                    asking.countDown()
                    release.await(20, TimeUnit.SECONDS)
                    PassphraseReply("typed".toCharArray(), false, false)
                },
            )
        var result: CharArray? = CharArray(0)
        val thread = Thread.ofVirtual().start { result = openedFor(first).answer(first, 10, key, ui) }
        assertTrue(asking.await(20, TimeUnit.SECONDS))

        passphrases.closed(first)
        release.countDown()
        thread.join(20_000)

        assertNull(result)
        assertEquals(listOf(5), zeroed.map { it.size }, "the typed copy is zeroed")
    }

    @Test
    fun `a session closed while the saved value is read gets no answer and keeps nothing`() {
        saved.save(key, "from-saved".toCharArray())
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        beforeDecrypt = {
            reading.countDown()
            release.await(20, TimeUnit.SECONDS)
        }
        var result: CharArray? = CharArray(0)
        val ui = FakeUi(reply())
        val thread = Thread.ofVirtual().start { result = openedFor(first).answer(first, 10, key, ui) }
        assertTrue(reading.await(20, TimeUnit.SECONDS))

        passphrases.closed(first)
        release.countDown()
        thread.join(20_000)

        assertNull(result)
        assertEquals(0, ui.calls, "a closed session's operation is over: no dialog")
        assertEquals(listOf(10), zeroed.map { it.size })
    }

    @Test
    fun `the end of a session that asked nothing, or the close of an unknown one, does nothing`() {
        val ui = FakeUi(reply())

        passphrases.ended(SessionId(77), true, ui)
        passphrases.closed(SessionId(78))

        assertTrue(jobs.isEmpty())
        assertEquals(emptyList(), zeroed)
    }

    @Test
    fun `destroy forgets memory and the pending answers of open sessions, zeroed`() {
        val ui = FakeUi(reply("open"))
        answer(ui, session = second)
        remember("remembered")
        zeroed.clear()

        passphrases.destroy()

        assertEquals(listOf(10, 4), zeroed.map { it.size }.sortedDescending())
        assertNull(passphrases.answer(second, 11, key, FakeUi(reply("again"))), "the open session is gone")
        assertEquals("again2", answer(FakeUi(reply("again2")), session = SessionId(8)), "memory is gone too")
    }

    @Test
    fun `a closed session gets no answer even if memory has one`() {
        remember()
        openedFor(first)
        passphrases.closed(first)

        assertNull(passphrases.answer(first, 10, key, FakeUi(reply())))
    }

    private companion object {
        @JvmStatic
        fun agentCases() =
            listOf(
                Arguments.of(emptyMap<String, String>(), "file", "no ssh agent"),
                Arguments.of(mapOf("SSH_AUTH_SOCK" to ""), "file", "no ssh agent"),
                Arguments.of(mapOf("SSH_AUTH_SOCK" to "/tmp/a"), "missing", "key file not found"),
                Arguments.of(mapOf("SSH_AUTH_SOCK" to "/tmp/a"), "folder", "key file not found"),
                Arguments.of(mapOf("SSH_AUTH_SOCK" to "/tmp/a"), "file", null),
            )
    }
}
