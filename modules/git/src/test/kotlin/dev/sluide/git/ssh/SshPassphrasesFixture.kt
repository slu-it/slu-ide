package dev.sluide.git.ssh

import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.secrets.Decrypted
import dev.sluide.secrets.Encrypted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/**
 * What the tests of [SshPassphrases] share, without ssh: a fake cipher, a real [ProjectDataStore] in a temp folder, a
 * launcher that runs the commits on virtual threads and fails the test if one throws. The rules are in
 * [SshPassphrasesTest] and [SshPassphrasesCommitTest]; what git and ssh do around them (the helper, the processes)
 * is in [SshPromptsTest].
 */
internal abstract class SshPassphrasesFixture {
    @TempDir
    lateinit var folder: Path

    protected val mapper = JsonMapper()
    protected val home get() = folder.resolve("home")
    protected val key by lazy {
        home.resolve(".ssh/id_ed25519").also { it.parent.createDirectories() }.also { it.writeText("k") }
    }
    protected val context get() = "git/ssh-passphrase/$key"

    protected var saveUnavailable: String? = null
    protected var noKey: String? = null
    protected var encryptFailure: Encrypted.Failed? = null
    protected var beforeEncrypt: () -> Unit = {}
    protected var environment = mapOf("SSH_AUTH_SOCK" to "/tmp/agent.sock")
    protected var sshAdd = "ssh-add"
    protected val jobs = CopyOnWriteArrayList<Thread>()
    protected val zeroed = CopyOnWriteArrayList<CharArray>()
    protected val failures = CopyOnWriteArrayList<Throwable>()
    protected var beforeDecrypt: () -> Unit = {}
    protected var encryptions = 0

    protected val store by lazy {
        ProjectDataStore(
            folder.resolve("projects"),
            folder.resolve("shared.json"),
            emptyList(),
        )
    }
    protected val saved by lazy {
        SavedSshPassphrases(
            store,
            { plain, context ->
                encryptions++
                beforeEncrypt()
                encryptFailure ?: Encrypted.Value("v1:${String(plain)}|$context")
            },
            { value, context ->
                beforeDecrypt()
                noKey?.let { return@SavedSshPassphrases Decrypted.NoKey(it) }
                if (value.endsWith("|$context")) {
                    Decrypted.Plain(value.removePrefix("v1:").removeSuffix("|$context").toCharArray())
                } else {
                    Decrypted.Unreadable
                }
            },
            { saveUnavailable },
            home,
        )
    }
    protected val channel by lazy { testChannel(folder) }
    private val openedSessions = HashSet<SessionId>()
    protected val passphrases by lazy {
        SshPassphrases(
            channel,
            saved,
            environment,
            SshAdd(channel, environment, sshAdd),
            {
                jobs +=
                    Thread.ofVirtual().start {
                        try {
                            it.run()
                        } catch (t: Throwable) {
                            failures += t
                        }
                    }
            },
        ).also { it.onZeroed = zeroed::add }
    }

    @AfterEach
    fun stop() {
        passphrases.destroy()
        channel.destroy()
        assertEquals(emptyList(), failures, "no commit failed")
    }

    protected val first = SessionId(1)
    protected val second = SessionId(2)

    protected fun reply(
        passphrase: String = "entered",
        save: Boolean = false,
        agent: Boolean = false,
    ) = { PassphraseReply(passphrase.toCharArray(), save, agent) }

    protected fun answer(
        ui: SshPromptUi,
        session: SessionId = first,
        connection: Long = 10,
    ) = openedFor(session).answer(session, connection, key, ui)?.let { String(it) }

    /** The passphrases, with [session] opened the first time a test uses it (SshPrompts does it before git runs). */
    protected fun openedFor(session: SessionId): SshPassphrases {
        if (openedSessions.add(session)) passphrases.opened(session)
        return passphrases
    }

    /** Waits for the commits that [SshPassphrases.ended] started off the calling thread. */
    protected fun awaitJobs() = jobs.forEach { it.join(20_000) }

    protected fun ended(
        ui: SshPromptUi,
        success: Boolean = true,
        session: SessionId = first,
    ) {
        passphrases.ended(session, success, ui)
        awaitJobs()
    }

    protected fun stored() = store.readShared()?.get("git")

    /** Memory holds [passphrase] for the key: entered and committed by a session that is closed again. */
    protected fun remember(passphrase: String = "remembered") {
        val ui = FakeUi(reply(passphrase))
        answer(ui, session = SessionId(99))
        ended(ui, session = SessionId(99))
        passphrases.closed(SessionId(99))
    }

    /** A stand-in `ssh-add` that asks its askpass once and records what it got; [rest] decides how it ends. */
    protected fun fakeSshAdd(rest: String): String {
        val file = folder.resolve("fake-ssh-add")
        file.writeText(
            "#!/bin/sh\n" +
                "echo \"\$2\" > '$folder/added-key'\n" +
                "\"\$SSH_ASKPASS\" \"Enter passphrase for \$2: \" > '$folder/added-passphrase'\n" +
                rest + "\n",
        )
        file.toFile().setExecutable(true)
        return file.toString()
    }
}
