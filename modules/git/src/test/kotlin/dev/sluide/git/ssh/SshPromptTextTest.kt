package dev.sluide.git.ssh

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The prompt texts recorded from OpenSSH (`src/test/resources/ssh-askpass/`). */
internal fun recordedPrompt(name: String): String =
    requireNotNull(SshPromptTextTest::class.java.getResourceAsStream("/ssh-askpass/$name")) { name }
        .use { it.readAllBytes().decodeToString() }

class SshPromptTextTest {
    @Test
    fun `the recorded unknown host prompt is an unknown host, shown without its question line`() {
        assertEquals(
            SshPrompt.UnknownHost(
                "The authenticity of host '[127.0.0.1]:22022 ([127.0.0.1]:22022)' can't be established.\n" +
                    "ED25519 key fingerprint is SHA256:BQajFzfTvf7TWfqK6E/wulr5iqYvzGegxR1bTVTut24.\n" +
                    "This key is not known by any other names.",
            ),
            sshPrompt(recordedPrompt("unknown-host.txt")),
        )
    }

    @Test
    fun `a host prompt with more lines before the question is still an unknown host`() {
        val text =
            "The authenticity of host 'git.example (10.0.0.1)' can't be established.\n" +
                "ED25519 key fingerprint is SHA256:abc.\n" +
                "This host key is known by the following other names/addresses:\n" +
                "    ~/.ssh/known_hosts:3: other.example\n" +
                "    ~/.ssh/known_hosts:7: 10.0.0.2"

        assertEquals(
            SshPrompt.UnknownHost(text),
            sshPrompt("$text\nAre you sure you want to continue connecting (yes/no/[fingerprint])? "),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // Starts like the host prompt, but the last line asks something else.
            "The authenticity of host 'h' can't be established.\nED25519 key fingerprint is SHA256:abc.\nPassword: ",
            // The question, but not after the host prompt's start.
            "Something else.\nAre you sure you want to continue connecting (yes/no/[fingerprint])? ",
            // The question on the only line.
            "The authenticity of host Are you sure you want to continue connecting (yes/no/[fingerprint])? ",
            "user@host's password: ",
            "Enter PIN for ED25519-SK key /home/u/.ssh/id_ed25519_sk: ",
            "Username for 'https://github.com': ",
            "",
            // Not the prefix exactly.
            "enter passphrase for key 'k1': ",
            "Enter passphrase for key 'k1':",
            // An empty path is no key.
            "Enter passphrase for key '': ",
            // Prefix and suffix sharing the quote.
            "Enter passphrase for key ': ",
        ],
    )
    fun `other prompts are other`(text: String) {
        assertEquals(SshPrompt.Other, sshPrompt(text))
    }

    @ParameterizedTest
    @ValueSource(strings = ["unknown-host-reprompt.txt", "ssh-add-passphrase.txt", "ssh-add-bad-passphrase.txt"])
    fun `the recorded re-prompt and ssh-add's prompts are other`(name: String) {
        assertEquals(SshPrompt.Other, sshPrompt(recordedPrompt(name)))
    }

    @Test
    fun `the recorded passphrase prompt names its key`() {
        assertEquals(SshPrompt.KeyPassphrase("k1"), sshPrompt(recordedPrompt("key-passphrase.txt")))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/home/u/.ssh/id_ed25519",
            "../keys/my key",
            "it's mine",
            "a': b",
            "line\nbreak",
            "/home/jürgen/.ssh/schlüssel",
            "'",
            "x",
        ],
    )
    fun `a passphrase prompt's path is what lies between the fixed prefix and suffix`(path: String) {
        assertEquals(SshPrompt.KeyPassphrase(path), sshPrompt("Enter passphrase for key '$path': "))
    }

    @Test
    fun `a path ssh cut at 100 characters is taken as shown`() {
        val cut = "/home/u/" + "k".repeat(92)

        assertEquals(100, cut.length)
        assertEquals(SshPrompt.KeyPassphrase(cut), sshPrompt("Enter passphrase for key '$cut': "))
    }

    @Test
    fun `a relative key path is resolved against the repository and normalized, an absolute one only normalized`() {
        val root = Path.of("/work/repo")

        assertEquals(Path.of("/work/repo/k1"), keyFile(root, "k1"))
        assertEquals(Path.of("/work/keys/k1"), keyFile(root, "../keys/k1"))
        assertEquals(Path.of("/home/u/.ssh/k1"), keyFile(root, "/home/u/.ssh/../.ssh/./k1"))
        assertEquals(Path.of("/work/repo/it's mine"), keyFile(root, "it's mine"))
    }

    @Test
    fun `a key path that is no path is none`() {
        assertNull(keyFile(Path.of("/work/repo"), "k\u0000ey"))
    }
}
