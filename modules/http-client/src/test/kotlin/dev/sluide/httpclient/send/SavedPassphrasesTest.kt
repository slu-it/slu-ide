package dev.sluide.httpclient.send

import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.secrets.Decrypted
import dev.sluide.secrets.Encrypted
import dev.sluide.secrets.SavedSecretEntry
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedPassphrasesTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var store: ProjectDataStore
    private lateinit var saved: SavedPassphrases
    private val mapper = JsonMapper()
    private var unavailable: String? = null
    private var noKey: String? = null
    private var beforeDecrypt: () -> Unit = {}
    private var encryptFailure: Encrypted.Failed? = null
    private var thrown: RuntimeException? = null

    /** A fake cipher: the value names the passphrase and its context, and decrypts only for that context. */
    private fun encrypt(
        plain: CharArray,
        context: String,
    ): Encrypted {
        thrown?.let { throw it }
        return encryptFailure ?: Encrypted.Value("v1:${String(plain)}|$context")
    }

    private fun decrypt(
        value: String,
        context: String,
    ): Decrypted {
        beforeDecrypt()
        thrown?.let { throw it }
        noKey?.let { return Decrypted.NoKey(it) }
        val prefix = "v1:"
        val suffix = "|$context"
        if (!value.startsWith(prefix) || !value.endsWith(suffix)) return Decrypted.Unreadable
        return Decrypted.Plain(value.removePrefix(prefix).removeSuffix(suffix).toCharArray())
    }

    private val certificate get() = dir.resolve("certs/client.pem")
    private val key get() = dir.resolve("certs/client.key")
    private val files get() = listOf(certificate, key)
    private val context get() = "http-client/passphrase/$certificate\n$key"

    /** [context] inside a JSON string. */
    private val contextJson get() = context.replace("\n", "\\n")

    @BeforeTest
    fun setUp() {
        store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        saved = SavedPassphrases(store, ::encrypt, ::decrypt) { unavailable }
    }

    private fun stored() = store.readShared()?.get("httpClient")

    private fun seed(json: String) {
        store.updateShared { it.set("httpClient", mapper.readTree(json)) }
    }

    private fun plain(decrypted: Decrypted?) = String(assertIs<Decrypted.Plain>(decrypted).value)

    @Test
    fun `saves under the normalized absolute paths and loads with the same context`() {
        val result = saved.save(listOf(dir.resolve("certs/../certs/client.pem"), key), "secret".toCharArray())

        assertEquals(Encrypted.Value("v1:secret|$context"), result)
        assertEquals(
            mapper.readTree("""{"passphrases":[{"files":["$certificate","$key"],"value":"v1:secret|$contextJson"}]}"""),
            stored(),
        )
        assertEquals("secret", plain(saved.load(files)))
        assertNull(saved.load(listOf(key)))
    }

    @Test
    fun `replaces a saved passphrase and removes it`() {
        saved.save(files, "old".toCharArray())
        saved.save(listOf(key), "other".toCharArray())

        saved.save(files, "new".toCharArray())
        assertEquals("new", plain(saved.load(files)))
        assertEquals(2, stored()?.get("passphrases")?.size())

        saved.remove(files)
        assertNull(saved.load(files))
        assertEquals("other", plain(saved.load(listOf(key))))
    }

    @Test
    fun `removes an unreadable value when it is loaded but keeps one without a key`() {
        seed("""{"passphrases":[{"files":["$certificate","$key"],"value":"v1:secret|elsewhere"}]}""")
        noKey = "Keychain access denied"
        assertIs<Decrypted.NoKey>(saved.load(files))
        assertEquals(1, stored()?.get("passphrases")?.size())

        noKey = null
        assertEquals(Decrypted.Unreadable, saved.load(files))

        assertNull(stored())
        assertNull(saved.load(files))
    }

    @Test
    fun `keeps a value saved while the old one was found unreadable`() {
        seed("""{"passphrases":[{"files":["$certificate","$key"],"value":"v1:secret|elsewhere"}]}""")
        beforeDecrypt = {
            beforeDecrypt = {}
            saved.save(files, "new".toCharArray())
        }

        assertEquals(Decrypted.Unreadable, saved.load(files))

        assertEquals("new", plain(saved.load(files)))
    }

    @Test
    fun `shows a stored path it cannot parse as written`() {
        seed("""{"passphrases":[{"files":["bad\u0000path"],"value":"v1:x"}]}""")

        assertEquals(listOf(SavedSecretEntry("bad\u0000path", "bad\u0000path", "bad\u0000path")), saved.entries())
    }

    @Test
    fun `shows a stored path without a file name as written`() {
        seed("""{"passphrases":[{"files":["/"],"value":"v1:x"}]}""")

        assertEquals(listOf("/"), saved.entries().map { it.label })
    }

    @Test
    fun `keeps unknown keys and entries it cannot read`() {
        seed(
            """{"later":1,"passphrases":[{"files":["$certificate","$key"],"value":"v1:old|$contextJson",""" +
                """"note":"x"},{"files":[],"value":"v1:y"},"broken"]}""",
        )

        saved.save(files, "new".toCharArray())
        saved.remove(listOf(key))

        assertEquals(
            mapper.readTree(
                """{"later":1,"passphrases":[{"files":["$certificate","$key"],"value":"v1:new|$contextJson",""" +
                    """"note":"x"},{"files":[],"value":"v1:y"},"broken"]}""",
            ),
            stored(),
        )
        saved.remove(files)
        assertEquals(mapper.readTree("""{"later":1,"passphrases":[{"files":[],"value":"v1:y"},"broken"]}"""), stored())
    }

    @Test
    fun `lists and removes the saved passphrases for Settings - Secrets`() {
        saved.save(files, "secret".toCharArray())
        saved.save(listOf(dir.resolve("client.p12")), "other".toCharArray())

        assertEquals("HTTP client certificate passphrases", saved.title)
        assertEquals(
            listOf(
                SavedSecretEntry("$certificate\n$key", "client.key", "$certificate\n$key"),
                SavedSecretEntry("${dir.resolve("client.p12")}", "client.p12", "${dir.resolve("client.p12")}"),
            ),
            saved.entries(),
        )

        assertTrue(saved.remove(setOf("$certificate\n$key")))
        assertEquals(listOf("client.p12"), saved.entries().map { it.label })
    }

    private val logName = SavedPassphrases::class.java.name

    @Test
    fun `a failing secret store counts as nothing saved and its log names no secret`() {
        LogCapture(logName).use { log ->
            thrown = IllegalStateException("hunter2 is the passphrase")

            assertEquals(Encrypted.Failed("the secret store failed"), saved.save(files, "hunter2".toCharArray()))
            assertNull(stored())

            thrown = null
            saved.save(files, "hunter2".toCharArray())
            thrown = IllegalStateException("hunter2 is the passphrase")
            assertNull(saved.load(files))
            assertEquals("the secret store failed", failingUnavailable().unavailableReason())

            assertEquals(
                listOf(
                    "Cannot encrypt a passphrase: java.lang.IllegalStateException",
                    "Cannot decrypt a saved passphrase: java.lang.IllegalStateException",
                    "Cannot tell whether passphrases can be saved: java.lang.IllegalStateException",
                ),
                log.lines.toList(),
            )
            assertTrue(log.lines.none { "hunter2" in it })
        }
    }

    private fun failingUnavailable() =
        SavedPassphrases(store, ::encrypt, ::decrypt) { throw IllegalStateException("hunter2 is the passphrase") }

    @Test
    fun `a refusal of the secret store is passed on and nothing is stored`() {
        encryptFailure = Encrypted.Failed("needs secret-tool")

        assertEquals(Encrypted.Failed("needs secret-tool"), saved.save(files, "x".toCharArray()))

        assertNull(stored())
    }

    @Test
    fun `an unreadable shared file cannot be written and lists nothing`() {
        dir.resolve("shared.json").writeText("{ not json")
        LogCapture(logName).use { log ->
            assertEquals(
                Encrypted.Failed("~/.sluide/shared.json cannot be written"),
                saved.save(files, "x".toCharArray()),
            )
            assertEquals(false, saved.remove(setOf("a")))
            assertEquals(emptyList(), saved.entries())
            assertNull(saved.load(files))
            assertEquals(emptyList(), log.lines.toList())
        }
        assertEquals("{ not json", dir.resolve("shared.json").readText())
    }

    @Test
    fun `entries without readable files or value are skipped and a repeated one counts once`() {
        seed(
            """{"passphrases":[
                {"files":[1],"value":"v1:a"},
                {"files":"x","value":"v1:b"},
                {"files":["a"],"value":5},
                {"files":["a"]},
                {"files":["$certificate","$key"],"value":"v1:first|$contextJson"},
                {"files":["$certificate","$key"],"value":"v1:second|$contextJson"}
            ]}""",
        )

        assertEquals(listOf("$certificate\n$key"), saved.entries().map { it.id })
        assertEquals("first", plain(saved.load(files)))
    }

    @Test
    fun `removing by id keeps the others, also entries it cannot read`() {
        seed(
            """{"passphrases":[{"files":["a"],"value":"v1:a"},{"files":["b"],"value":"v1:b"},"broken",{"files":[],"value":"v1:c"}]}""",
        )

        assertTrue(saved.remove(setOf("a")))
        assertEquals(listOf("b"), saved.entries().map { it.id })
        assertTrue(saved.remove(setOf("unknown")))

        assertEquals(
            mapper.readTree(
                """{"passphrases":[{"files":["b"],"value":"v1:b"},"broken",{"files":[],"value":"v1:c"}]}""",
            ),
            stored(),
        )
    }

    @Test
    fun `the passphrase store of SslContexts goes through the same functions`() {
        val asStore = saved.asPassphraseStore()

        assertEquals(Encrypted.Value("v1:pw|$context"), asStore.save(files, "pw".toCharArray()))
        assertEquals("pw", plain(asStore.load(files)))
        asStore.remove(files)
        assertNull(asStore.load(files))
    }

    @Test
    fun `replaces a shared httpClient field that is not an object`() {
        store.updateShared { it.put("httpClient", "oops") }

        saved.save(files, "pw".toCharArray())

        assertEquals("pw", plain(saved.load(files)))
    }

    @Test
    fun `tells why nothing can be saved`() {
        assertNull(saved.unavailableReason())
        unavailable = "needs secret-tool"

        assertEquals("needs secret-tool", saved.unavailableReason())
        assertEquals("needs secret-tool", saved.asPassphraseStore().unavailableReason())
    }
}
