package dev.sluide.git.ssh

import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.secrets.Decrypted
import dev.sluide.secrets.Encrypted
import dev.sluide.secrets.SavedSecretEntry
import dev.sluide.secrets.Secrets
import dev.sluide.testsupport.LogCapture
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedSshPassphrasesTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var store: ProjectDataStore
    private lateinit var saved: SavedSshPassphrases
    private val mapper = JsonMapper()
    private var unavailable: String? = null
    private var noKey: String? = null
    private var encryptFailure: Encrypted.Failed? = null
    private var beforeDecrypt: () -> Unit = {}
    private val decrypted = mutableListOf<CharArray>()
    private val logName = SavedSshPassphrases::class.java.name

    private val home get() = dir.resolve("home")
    private val key get() = home.resolve(".ssh/id_ed25519")
    private val context get() = "git/ssh-passphrase/$key"

    /** A fake cipher: the value names the passphrase and its context, and decrypts only for that context. */
    private fun encrypt(
        plain: CharArray,
        context: String,
    ): Encrypted = encryptFailure ?: Encrypted.Value("v1:${String(plain)}|$context")

    private fun decrypt(
        value: String,
        context: String,
    ): Decrypted {
        beforeDecrypt().also { beforeDecrypt = {} }
        noKey?.let { return Decrypted.NoKey(it) }
        val suffix = "|$context"
        if (!value.startsWith("v1:") || !value.endsWith(suffix)) return Decrypted.Unreadable
        return Decrypted.Plain(
            value
                .removePrefix("v1:")
                .removeSuffix(suffix)
                .toCharArray()
                .also(decrypted::add),
        )
    }

    @BeforeTest
    fun setUp() {
        store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        saved = SavedSshPassphrases(store, ::encrypt, ::decrypt, { unavailable }, home)
    }

    private fun stored() = store.readShared()?.get("git")

    private fun seed(json: String) {
        store.updateShared { it.set("git", mapper.readTree(json)) }
    }

    @Test
    fun `saves under the key path in the git field and loads it with the same context`() {
        val result = saved.save(key, "secret".toCharArray())

        assertEquals(Encrypted.Value("v1:secret|$context"), result)
        assertEquals(
            mapper.readTree("""{"sshPassphrases":[{"key":"$key","value":"v1:secret|$context"}]}"""),
            stored(),
        )
        assertEquals("secret", String(assertIs<Decrypted.Plain>(saved.load(key)).value))
    }

    @Test
    fun `a relative or unnormalized key path is stored absolute and normalized`() {
        saved.save(home.resolve(".ssh/../.ssh/id_ed25519"), "secret".toCharArray())

        assertEquals(
            key.toString(),
            stored()
                ?.get("sshPassphrases")
                ?.get(0)
                ?.get("key")
                ?.stringValue(),
        )
    }

    @Test
    fun `replaces a saved passphrase, keeps others and removes only the exact entry`() {
        val other = home.resolve(".ssh/other")
        saved.save(key, "old".toCharArray())
        saved.save(other, "other".toCharArray())

        saved.save(key, "new".toCharArray())
        assertEquals("new", String(assertIs<Decrypted.Plain>(saved.load(key)).value))
        assertEquals(2, stored()?.get("sshPassphrases")?.size())

        saved.remove(key)
        assertNull(saved.load(key))
        assertEquals("other", String(assertIs<Decrypted.Plain>(saved.load(other)).value))
    }

    @Test
    fun `other fields of git survive a save and a remove, and an empty list leaves no field`() {
        seed("""{"future":{"a":1}}""")

        saved.save(key, "secret".toCharArray())
        assertEquals(1, stored()?.get("future")?.get("a")?.intValue())
        assertEquals(1, stored()?.get("sshPassphrases")?.size())

        saved.remove(key)
        assertEquals(mapper.readTree("""{"future":{"a":1}}"""), stored())

        seed("""{"sshPassphrases":[{"key":"$key","value":"v1:x|$context"}]}""")
        saved.remove(key)
        assertNull(stored())
    }

    @Test
    fun `an unreadable value is removed when it is loaded, one without a key stays`() {
        seed("""{"sshPassphrases":[{"key":"$key","value":"v1:secret|elsewhere"}]}""")
        noKey = "Keychain access denied"
        assertIs<Decrypted.NoKey>(saved.load(key))
        assertEquals(1, stored()?.get("sshPassphrases")?.size())

        noKey = null
        assertEquals(Decrypted.Unreadable, saved.load(key))
        assertNull(stored())
    }

    @Test
    fun `nothing saved loads as null`() {
        assertNull(saved.load(key))
    }

    @Test
    fun `a failed encryption is returned and stores nothing`() {
        encryptFailure = Encrypted.Failed("no keychain")

        assertEquals(Encrypted.Failed("no keychain"), saved.save(key, "secret".toCharArray()))
        assertNull(stored())
    }

    @Test
    fun `an encryption that throws is a failure that names the passphrase`() {
        val throwing =
            SavedSshPassphrases(store, { _, _ -> throw IllegalStateException("hunter2") }, ::decrypt, { null }, home)

        val result = assertIs<Encrypted.Failed>(throwing.save(key, "hunter2".toCharArray()))

        assertTrue("hunter2" !in result.reason)
        assertNull(stored())
    }

    @Test
    fun `a decryption that throws counts as nothing saved and keeps the entry`() {
        seed("""{"sshPassphrases":[{"key":"$key","value":"v1:x|$context"}]}""")
        val throwing = SavedSshPassphrases(store, ::encrypt, { _, _ -> throw IllegalStateException() }, { null }, home)

        assertNull(throwing.load(key))
        assertEquals(1, stored()?.get("sshPassphrases")?.size())
    }

    @Test
    fun `the unavailable reason is passed on, and a throwing source gives a reason`() {
        unavailable = "needs secret-tool"
        assertEquals("needs secret-tool", saved.unavailableReason())

        val throwing = SavedSshPassphrases(store, ::encrypt, ::decrypt, { throw IllegalStateException() }, home)
        assertEquals("the secret store failed", throwing.unavailableReason())
    }

    @Test
    fun `lists one entry per saved key, labelled with the home folder as tilde`() {
        val outside = dir.resolve("elsewhere/key")
        saved.save(key, "a".toCharArray())
        saved.save(outside, "b".toCharArray())

        assertEquals(
            listOf(
                SavedSecretEntry(key.toString(), "~/.ssh/id_ed25519", key.toString()),
                SavedSecretEntry(outside.toString(), outside.toString(), outside.toString()),
            ),
            saved.entries(),
        )
        assertEquals("Git SSH key passphrases", saved.title)
    }

    @Test
    fun `a malformed entry is skipped and survives a save`() {
        seed("""{"sshPassphrases":[{"key":5},{"key":"$key"},"x"]}""")

        assertEquals(emptyList(), saved.entries())
        saved.save(home.resolve("k"), "a".toCharArray())
        assertEquals(4, stored()?.get("sshPassphrases")?.size())
    }

    @Test
    fun `removing by id removes those entries only`() {
        val other = home.resolve(".ssh/other")
        saved.save(key, "a".toCharArray())
        saved.save(other, "b".toCharArray())

        assertTrue(saved.remove(setOf(key.toString())))

        assertEquals(listOf(other.toString()), saved.entries().map { it.id })
    }

    @Test
    fun `a value saved while an unreadable one is loaded stays`() {
        seed("""{"sshPassphrases":[{"key":"$key","value":"v1:old|elsewhere"}]}""")
        beforeDecrypt = { saved.save(key, "newer".toCharArray()) }

        assertEquals(Decrypted.Unreadable, saved.load(key))

        assertEquals("newer", String(assertIs<Decrypted.Plain>(saved.load(key)).value))
    }

    @Test
    fun `a key that is the home folder itself is shown as written`() {
        seed("""{"sshPassphrases":[{"key":"$home","value":"v1:x|y"}]}""")

        assertEquals(home.toString(), saved.entries().single().label)
    }

    @Test
    fun `a replaced entry keeps its other fields`() {
        seed("""{"sshPassphrases":[{"key":"$key","value":"v1:old|x","note":1}]}""")

        saved.save(key, "new".toCharArray())

        assertEquals(
            mapper.readTree("""{"sshPassphrases":[{"key":"$key","value":"v1:new|$context","note":1}]}"""),
            stored(),
        )
    }

    @Test
    fun `a git field that is no object is replaced when saving and lists nothing`() {
        seed("\"text\"")
        assertEquals(emptyList(), saved.entries())

        saved.save(key, "secret".toCharArray())

        assertEquals(1, stored()?.get("sshPassphrases")?.size())
    }

    @Test
    fun `a passphrase list that is no array lists nothing and is replaced when saving`() {
        seed("""{"sshPassphrases":5}""")
        assertEquals(emptyList(), saved.entries())

        saved.save(key, "secret".toCharArray())

        assertEquals(1, stored()?.get("sshPassphrases")?.size())
    }

    @Test
    fun `an entry needs a key and a value that are both text`() {
        seed(
            """{"sshPassphrases":[{"key":5,"value":"v1:a|b"},{"key":"a"},{"key":"b","value":5},""" +
                """{"value":"v1:e|f"},{"key":"c","value":"v1:c|d"}]}""",
        )

        assertEquals(listOf("c"), saved.entries().map { it.id })
    }

    @Test
    fun `removing ids that match nothing keeps every entry`() {
        saved.save(key, "a".toCharArray())

        assertTrue(saved.remove(setOf("no-such-key")))

        assertEquals(listOf(key.toString()), saved.entries().map { it.id })
    }

    @Test
    fun `an unreadable shared file cannot be written, lists nothing and says so`() {
        dir.resolve("shared.json").writeText("{ not json")

        assertEquals(Encrypted.Failed("~/.sluide/shared.json cannot be written"), saved.save(key, "x".toCharArray()))
        assertEquals(false, saved.remove(setOf("a")))
        assertEquals(emptyList(), saved.entries())
        saved.remove(key)
        assertNull(saved.load(key))
    }

    @Test
    fun `a store that throws counts as nothing saved and the log names only the class`() {
        val boom = { throw IllegalStateException("hunter2 is the passphrase") }
        val broken =
            SavedSshPassphrases({ boom() }, { boom() }, ::encrypt, ::decrypt, { null }, home)
        LogCapture(logName).use { log ->
            assertEquals(emptyList(), broken.entries())
            assertEquals(false, broken.remove(setOf("a")))
            assertEquals(2, log.messages().size)
            assertTrue(
                log.messages().all { "java.lang.IllegalStateException" in it && "hunter2" !in it },
                "${log.messages()}",
            )
        }
    }

    @Test
    fun `a failing secret store is logged by class and never by message`() {
        val boom = { throw IllegalStateException("hunter2 is the passphrase") }
        seed("""{"sshPassphrases":[{"key":"$key","value":"v1:x|$context"}]}""")
        val broken = SavedSshPassphrases(store, { _, _ -> boom() }, { _, _ -> boom() }, { boom() }, home)
        LogCapture(logName).use { log ->
            broken.unavailableReason()
            broken.load(key)
            broken.save(key, "hunter2".toCharArray())

            assertEquals(3, log.messages().size)
            assertTrue(
                log.messages().all { "java.lang.IllegalStateException" in it && "hunter2" !in it },
                "${log.messages()}",
            )
        }
    }

    @Test
    fun `removing leaves entries that cannot be read, by id and by key`() {
        seed(
            """{"sshPassphrases":[{"key":5},{"key":"$key","value":"v1:x|$context"},{"key":"other","value":"v1:y|z"}]}""",
        )

        assertTrue(saved.remove(setOf(key.toString())))
        assertEquals(listOf("other"), saved.entries().map { it.id })
        assertEquals(2, stored()?.get("sshPassphrases")?.size() ?: 0, "unreadable and other entries stay, key removed")

        saved.remove(Path.of("other"))
        assertEquals(listOf("other"), saved.entries().map { it.id }, "a relative path is another key")
    }

    @Test
    fun `another key's saved value is not this key's`() {
        saved.save(home.resolve("other"), "a".toCharArray())

        assertNull(saved.load(key))
    }

    @Test
    fun `the bean's own constructor uses the real Secrets, the real home folder and the shared data`() {
        val real = SavedSshPassphrases(store, Secrets())
        val inHome = Path.of(System.getProperty("user.home")).resolve(".ssh/id_x")
        seed("""{"sshPassphrases":[{"key":"$inHome","value":"not-encrypted"}]}""")

        assertEquals("~/.ssh/id_x", real.entries().single().label)
        assertEquals(Secrets().saveUnavailableReason(), real.unavailableReason())
        // A value that is no ciphertext is unreadable without touching the secret store's key.
        assertEquals(Decrypted.Unreadable, real.load(inHome))
        assertNull(stored())
    }

    @Test
    fun `removing a key leaves an entry that cannot be read`() {
        seed("""{"sshPassphrases":[{"key":5},{"key":"$key","value":"v1:x|$context"}]}""")

        saved.remove(key)

        assertEquals(1, stored()?.get("sshPassphrases")?.size())
        assertEquals(emptyList(), saved.entries())
        assertNull(saved.load(key))
    }

    @Test
    fun `a passphrase list that is an object is not a list of entries`() {
        seed("""{"sshPassphrases":{"a":{"key":"$key","value":"v1:x|$context"}}}""")

        assertEquals(emptyList(), saved.entries())
        assertNull(saved.load(key))
    }

    @Test
    fun `nothing saved for the key is nothing, without a failure in the log`() {
        saved.save(home.resolve("other"), "a".toCharArray())
        LogCapture(logName).use { log ->
            assertNull(saved.load(key))

            assertEquals(emptyList(), log.messages())
        }
    }

    @Test
    fun `without a shared file nothing is listed or loaded, without a failure in the log`() {
        LogCapture(logName).use { log ->
            assertEquals(emptyList(), saved.entries())
            assertNull(saved.load(key))

            assertEquals(emptyList(), log.messages())
        }
    }

    @Test
    fun `the Spring constructor hands encrypting and the reason to Secrets`() {
        val secrets = Secrets()
        // Only where the system has no secret store, so that no real one is asked (and no key created in it).
        assumeTrue(secrets.saveUnavailableReason() != null)
        val real = SavedSshPassphrases(store, secrets)

        assertEquals(secrets.saveUnavailableReason(), real.unavailableReason())
        assertIs<Encrypted.Failed>(real.save(key, "pw".toCharArray()))
    }

    @Test
    fun `a rejected passphrase removes its entry and no other`() {
        val other = home.resolve("other")
        saved.save(key, "old".toCharArray())
        saved.save(other, "keep".toCharArray())
        decrypted.clear()

        saved.removeRejected(key, "old".toCharArray())

        assertEquals(listOf(other.toString()), saved.entries().map { it.id })
        assertTrue(decrypted.all { chars -> chars.all { it == '\u0000' } } && decrypted.isNotEmpty(), "zeroed")
    }

    @Test
    fun `a rejected passphrase that is not the saved one removes nothing`() {
        saved.save(key, "newer".toCharArray())

        saved.removeRejected(key, "old".toCharArray())

        assertEquals("newer", String(assertIs<Decrypted.Plain>(saved.load(key)).value))
    }

    @Test
    fun `a rejected passphrase with nothing saved, or no key to read it, changes nothing and logs nothing`() {
        LogCapture(logName).use { log ->
            saved.removeRejected(key, "old".toCharArray())
            assertNull(stored())

            saved.save(key, "old".toCharArray())
            noKey = "locked"
            saved.removeRejected(key, "old".toCharArray())

            assertEquals(1, stored()?.get("sshPassphrases")?.size())
            assertEquals(emptyList(), log.messages())
        }
    }

    @Test
    fun `a rejected passphrase whose saved value cannot be decrypted is logged by class and kept`() {
        seed("""{"sshPassphrases":[{"key":"$key","value":"v1:x|$context"}]}""")
        val throwing =
            SavedSshPassphrases(store, ::encrypt, { _, _ -> throw IllegalStateException("hunter2") }, { null }, home)
        LogCapture(logName).use { log ->
            throwing.removeRejected(key, "x".toCharArray())

            assertEquals(1, stored()?.get("sshPassphrases")?.size())
            assertEquals(1, log.messages().size)
            assertTrue(
                "java.lang.IllegalStateException" in log.messages().single() && "hunter2" !in log.messages().single(),
            )
        }
    }
}
