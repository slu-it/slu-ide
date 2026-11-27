package dev.sluide.gitlog.actions

import dev.sluide.git.ssh.PassphraseNotice
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The words of the ssh prompt dialogs (ADR 0194). */
class SshPromptTextTest {
    private val home = Path.of("/home/me")

    @Test
    fun `a notice says why ssh asks again`() {
        assertEquals("Wrong passphrase, try again.", noticeText(PassphraseNotice.WRONG))
        assertEquals(
            "The saved passphrase was not accepted and was removed.",
            noticeText(PassphraseNotice.SAVED_REJECTED),
        )
    }

    @Test
    fun `a check box that cannot be checked names why`() {
        assertEquals("Save passphrase", checkBoxLabel("Save passphrase", null))
        assertEquals("Save passphrase (needs secret-tool)", checkBoxLabel("Save passphrase", "needs secret-tool"))
    }

    @Test
    fun `the home folder of a key's path is written as a tilde`() {
        assertEquals("~/.ssh/id_ed25519", abbreviateHome(Path.of("/home/me/.ssh/id_ed25519"), home))
        assertEquals("~", abbreviateHome(home, home))
        assertEquals("/home/mea/key", abbreviateHome(Path.of("/home/mea/key"), home), "only whole folder names")
        assertEquals("/etc/ssh/key", abbreviateHome(Path.of("/etc/ssh/key"), home))
        assertEquals("/home/me/key", abbreviateHome(Path.of("/home/me/key"), null))
    }

    @Test
    fun `the question names the key`() {
        assertEquals(
            "Enter the passphrase for ~/keys/work.",
            passphraseQuestion(Path.of("/home/me/keys/work"), home),
        )
    }

    @Test
    fun `unlock needs a passphrase`() {
        assertTrue(unlockDisabled(""))
        assertFalse(unlockDisabled(" "))
        assertFalse(unlockDisabled("secret"))
    }
}
