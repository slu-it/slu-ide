package dev.sluide.gitlog.actions

import dev.sluide.git.ssh.HostKeyRequest
import dev.sluide.git.ssh.PassphraseReply
import dev.sluide.git.ssh.PassphraseRequest
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How [FxSshPromptUi] hands its dialogs to the FX thread and waits on them, with fake dialogs and no toolkit. */
class FxSshPromptUiTest {
    private val request = PassphraseRequest(Path.of("/home/me/.ssh/id"), null, null, null)
    private val later = CopyOnWriteArrayList<Runnable>()
    private val infos = CopyOnWriteArrayList<String>()
    private val shownWith = CopyOnWriteArrayList<String>()

    /** A dialog that answers [answer] once [release] opens; [shown] opens when it is on screen. */
    private class FakeDialog<T>(
        private val answer: T?,
        private val release: CountDownLatch = CountDownLatch(0),
    ) : PromptDialog<T> {
        val shown = CountDownLatch(1)
        val closed = CountDownLatch(1)

        override fun showAndWait(): T? {
            shown.countDown()
            release.await(10, TimeUnit.SECONDS)
            return answer
        }

        override fun close() = closed.countDown()
    }

    private fun ui(
        passphrase: PromptDialog<PassphraseReply> = FakeDialog(null),
        host: PromptDialog<Boolean> = FakeDialog(null),
        eager: Boolean = true,
    ) = FxSshPromptUi(
        owner = { null },
        operation = { "Fetching origin…" },
        passphraseDialog = { _, line, _ ->
            shownWith += line
            passphrase
        },
        hostDialog = { _, line, _ ->
            shownWith += line
            host
        },
        infoAlert = { _, title, text -> infos += "$title: $text" },
        runLater = { if (eager) Thread(it).start() else later += it },
    )

    /** Runs [ask] on a thread of its own; its result is the answer, or the interrupt it ended with. */
    private fun inBackground(ask: () -> Any?): Pair<Thread, CompletableFuture<Any?>> {
        val result = CompletableFuture<Any?>()
        val thread =
            Thread {
                try {
                    result.complete(ask())
                } catch (e: InterruptedException) {
                    result.complete(e)
                }
            }
        thread.start()
        return thread to result
    }

    private fun askInBackground(ui: FxSshPromptUi) = inBackground { ui.askPassphrase(request) }

    /** The answer of [ask] given on a thread of its own, within a deadline. */
    private fun answerOf(ask: () -> Any?): Any? = inBackground(ask).second.get(10, TimeUnit.SECONDS)

    @Test
    fun `a passphrase asked on another thread is the dialog's answer, made with the operation line`() {
        val reply = PassphraseReply("secret".toCharArray(), save = true, addToAgent = false)
        val ui = ui(passphrase = FakeDialog(reply))

        assertEquals(reply, answerOf { ui.askPassphrase(request) })
        assertEquals(listOf("Fetching origin…"), shownWith)
    }

    @Test
    fun `a dismissed passphrase dialog is Cancel`() {
        val ui = ui(passphrase = FakeDialog(null))

        assertNull(answerOf { ui.askPassphrase(request) })
    }

    @Test
    fun `a host is trusted only if the dialog says so`() {
        assertEquals(true, answerOf { ui(host = FakeDialog(true)).confirmHost(HostKeyRequest("text")) })
        assertEquals(false, answerOf { ui(host = FakeDialog(false)).confirmHost(HostKeyRequest("text")) })
        assertEquals(false, answerOf { ui(host = FakeDialog(null)).confirmHost(HostKeyRequest("text")) }, "dismissed")
    }

    @Test
    fun `a dialog that cannot be made counts as Cancel`() {
        val broken =
            FxSshPromptUi(
                owner = { null },
                operation = { "" },
                passphraseDialog = { _, _, _ -> throw IllegalStateException("no toolkit") },
                runLater = { Thread(it).start() },
            )

        assertNull(answerOf { broken.askPassphrase(request) })
    }

    @Test
    fun `interrupting the waiting thread closes the dialog and passes the interrupt on`() {
        val dialog = FakeDialog(PassphraseReply(charArrayOf(), false, false), CountDownLatch(1))
        val (waiting, result) = askInBackground(ui(passphrase = dialog))
        // Either the dialog is shown or, wrongly, the ask ended at once: no wait for a dialog that never comes.
        CompletableFuture
            .anyOf(
                CompletableFuture.supplyAsync { dialog.shown.await(10, TimeUnit.SECONDS) },
                result,
            ).get()
        assertEquals(0, dialog.shown.count, "the dialog is shown")
        assertFalse(result.isDone, "still waiting")

        waiting.interrupt()

        assertTrue(result.get(10, TimeUnit.SECONDS) is InterruptedException)
        assertTrue(dialog.closed.await(10, TimeUnit.SECONDS), "closed")
    }

    @Test
    fun `an interrupt before the FX thread got to it shows no dialog at all`() {
        val dialog = FakeDialog(PassphraseReply(charArrayOf(), false, false))
        val (waiting, result) = askInBackground(ui(passphrase = dialog, eager = false))
        // The request waits in the queue of the FX thread.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (later.isEmpty() && !result.isDone && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse(result.isDone, "waiting for the FX thread")
        assertFalse(later.isEmpty(), "queued for the FX thread")

        waiting.interrupt()
        assertTrue(result.get(10, TimeUnit.SECONDS) is InterruptedException)
        later.toList().forEach(Runnable::run)

        assertEquals(emptyList(), shownWith)
        assertEquals(1, dialog.closed.count, "nothing to close")
    }

    @Test
    fun `a passphrase typed after the interrupt is zeroed, not handed to anyone`() {
        val typed = "secret".toCharArray()
        val release = CountDownLatch(1)
        val dialog = FakeDialog(PassphraseReply(typed, false, false), release)
        val (waiting, result) = askInBackground(ui(passphrase = dialog))
        assertTrue(dialog.shown.await(10, TimeUnit.SECONDS))

        waiting.interrupt()
        assertTrue(result.get(10, TimeUnit.SECONDS) is InterruptedException)
        // Unlock was clicked as the interrupt came.
        release.countDown()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (typed.any { it != '\u0000' } && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("\u0000".repeat(6), String(typed))
    }

    @Test
    fun `the two info alerts say what failed, through the FX thread, without waiting`() {
        val ui = ui(eager = false)

        ui.notSaved("needs secret-tool")
        ui.notAddedToAgent("Could not open a connection to your authentication agent.")

        assertEquals(emptyList(), infos, "not before the FX thread runs them")
        later.forEach(Runnable::run)
        assertEquals(
            listOf(
                "Passphrase Not Saved: needs secret-tool",
                "Key Not Added to SSH Agent: Could not open a connection to your authentication agent.",
            ),
            infos,
        )
    }
}
