package dev.sluide.httpclient.send

import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.secrets.Decrypted
import dev.sluide.secrets.Encrypted
import dev.sluide.secrets.SavedSecretEntry
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
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

    /** A fake cipher: the value names the passphrase and its context, and decrypts only for that context. */
    private fun encrypt(
        plain: CharArray,
        context: String,
    ): Encrypted = Encrypted.Value("v1:${String(plain)}|$context")

    private fun decrypt(
        value: String,
        context: String,
    ): Decrypted {
        beforeDecrypt()
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

    @Test
    fun `tells why nothing can be saved`() {
        assertNull(saved.unavailableReason())
        unavailable = "needs secret-tool"

        assertEquals("needs secret-tool", saved.unavailableReason())
        assertEquals("needs secret-tool", saved.asPassphraseStore().unavailableReason())
    }
}
