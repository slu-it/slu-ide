package dev.sluide.git.cli

import dev.sluide.git.ssh.SshAccess
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class SshEnvironmentTest {
    private val batch = SshAccess.Batch
    private val plain = SshSettings(sshChosen = false, askPass = false)
    private val chosen = SshSettings(sshChosen = true, askPass = false)
    private val session = FakePrompting(mapOf("SSH_ASKPASS" to "/h/askpass", "SSH_ASKPASS_REQUIRE" to "force"))

    @Test
    fun `the batch mode is added only where git would run plain ssh, whatever the askpass settings`() {
        val batchMode = mapOf("GIT_SSH_COMMAND" to "ssh -o BatchMode=yes")
        assertEquals(batchMode, sshEnvironment(mapOf("PATH" to "/bin"), plain, batch))
        assertEquals(batchMode, sshEnvironment(emptyMap(), plain.copy(askPass = true), batch))
        assertEquals(emptyMap(), sshEnvironment(emptyMap(), chosen, batch))
        assertEquals(emptyMap(), sshEnvironment(mapOf("GIT_SSH_COMMAND" to "ssh -i key"), plain, batch))
        assertEquals(emptyMap(), sshEnvironment(mapOf("GIT_SSH" to "/usr/bin/plink"), plain, batch))
        assertEquals(emptyMap(), sshEnvironment(mapOf("GIT_SSH_VARIANT" to "plink"), plain, batch))
    }

    @Test
    fun `a session where git runs plain ssh turns password logins off and git's own askpass chain`() {
        assertEquals(
            session.environment +
                mapOf(
                    "GIT_SSH_COMMAND" to "ssh -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no",
                    "GIT_ASKPASS" to "",
                ),
            sshEnvironment(mapOf("PATH" to "/bin"), plain, session),
        )
    }

    @Test
    fun `with the user's own ssh command a session adds only its variables and the empty GIT_ASKPASS`() {
        val withAskpassOff = session.environment + ("GIT_ASKPASS" to "")

        assertEquals(withAskpassOff, sshEnvironment(emptyMap(), chosen, session))
        assertEquals(withAskpassOff, sshEnvironment(mapOf("GIT_SSH_COMMAND" to "ssh -i key"), plain, session))
        assertEquals(withAskpassOff, sshEnvironment(mapOf("GIT_SSH" to "/usr/bin/plink"), plain, session))
        assertEquals(withAskpassOff, sshEnvironment(mapOf("GIT_SSH_VARIANT" to "plink"), plain, session))
    }

    @Test
    fun `the user's own GIT_ASKPASS or core askPass is left to git`() {
        assertEquals(
            session.environment,
            sshEnvironment(mapOf("GIT_ASKPASS" to "/usr/bin/ksshaskpass"), chosen, session),
        )
        assertEquals(session.environment, sshEnvironment(emptyMap(), chosen.copy(askPass = true), session))
        assertEquals(
            session.environment +
                ("GIT_SSH_COMMAND" to "ssh -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no"),
            sshEnvironment(emptyMap(), plain.copy(askPass = true), session),
        )
    }
}

/** A session that only gives its [environment] and records how each git process [ended]. */
internal class FakePrompting(
    override val environment: Map<String, String> =
        mapOf(
            "SSH_ASKPASS" to "/h/askpass",
            "SSH_ASKPASS_REQUIRE" to "force",
            "SLUIDE_ASKPASS_SOCKET" to "/t/socket",
            "SLUIDE_ASKPASS_TOKEN" to "c0ffee",
        ),
) : SshAccess.Prompting {
    /** Each git process's success, in order. */
    val outcomes = CopyOnWriteArrayList<Boolean>()

    override fun ended(success: Boolean) {
        outcomes += success
    }

    override fun close() = Unit
}

/**
 * What the env-echoing fake gits of the push tests log for a [FakePrompting] session where git runs plain ssh:
 * `$GIT_SSH_COMMAND|$SSH_ASKPASS $SSH_ASKPASS_REQUIRE $SLUIDE_ASKPASS_SOCKET $SLUIDE_ASKPASS_TOKEN`.
 */
internal const val SESSION_LINE =
    "ssh -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no|/h/askpass force /t/socket c0ffee"
