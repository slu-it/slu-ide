package dev.sluide.java.settings

import dev.sluide.java.JavaHomes
import dev.sluide.java.JavaProjectSettings
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The project JDK page without a scene: its entries, loading, Other… and apply (ADR 0179, ADR 0084). */
class JavaProjectSettingsPageModelTest {
    @TempDir
    lateinit var dir: Path

    private val project: Path by lazy { Files.createDirectories(dir.resolve("work/app")) }
    private val userHome: Path by lazy { Files.createDirectories(dir.resolve("home")) }
    private val store by lazy { ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()) }
    private val settings by lazy { JavaProjectSettings(store) { it.run() } }
    private val ioQueue = ArrayDeque<Runnable>()
    private val fxQueue = ArrayDeque<Runnable>()
    private var loginShell: String? = ""
    private var shellFailure: RuntimeException? = null
    private var environment: Map<String, String> = emptyMap()
    private val thoreau by lazy { jdk(dir.resolve("thoreau-jdk"), "25.0.1") }

    private val model by lazy {
        JavaProjectSettingsPageModel(
            settings,
            JavaHomes(
                { _, _ ->
                    // The real loginShellPrints returns null instead of throwing: a throwing shell stands for any
                    // unchecked failure inside the loading work.
                    shellFailure?.let { throw it }
                    loginShell
                },
                environment,
                thoreau,
            ),
            userHome,
            false,
            thoreau,
            emptyList(),
            { ioQueue += it },
            { fxQueue += it },
        )
    }

    private fun jdk(
        home: Path,
        version: String = "21.0.1",
    ): Path {
        home
            .resolve("bin")
            .createDirectories()
            .resolve("java")
            .writeText("#!/bin/sh\n")
        home.resolve("release").writeText("IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"$version\"\n")
        return home
    }

    private fun run(queue: ArrayDeque<Runnable>) {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }

    /** Loads the page: the work on the io queue, then its result on the FX queue. */
    private fun load() {
        model.load(project)
        run(ioQueue)
        run(fxQueue)
    }

    private fun texts() = model.choices.map { it.text }

    @Test
    fun `until the work is done the page is loading and has no entries`() {
        model.load(project)

        assertTrue(model.loading.get())
        assertTrue(model.choices.isEmpty())
        assertNull(model.selected.get())
        run(ioQueue)
        assertTrue(model.loading.get(), "the result waits for the FX thread")
        run(fxQueue)
        assertFalse(model.loading.get())
    }

    @Test
    fun `the entries are JAVA_HOME, the installed JDKs, Thoreau's JDK and Other`() {
        val sdk = jdk(userHome.resolve(".sdkman/candidates/java/17.0.9-tem"), "17.0.9")
        val home = jdk(dir.resolve("login-jdk"), "21.0.2")
        loginShell = home.toString()

        load()

        assertEquals(
            listOf(
                "JAVA_HOME: 21.0.2 ($home)",
                "17.0.9 ($sdk)",
                "Thoreau's JDK: 25.0.1 ($thoreau)",
                "Other…",
            ),
            texts(),
        )
        assertTrue(model.choices.last().other)
        assertNull(model.choices.first().path)
        assertEquals(sdk, model.choices[1].path)
    }

    @Test
    fun `JAVA_HOME of Thoreau's environment counts when the login shell has none`() {
        val home = jdk(dir.resolve("env-jdk"), "11.0.2")
        environment = mapOf("JAVA_HOME" to home.toString())

        load()

        assertEquals("JAVA_HOME: 11.0.2 ($home)", texts().first())
    }

    @Test
    fun `without a usable JAVA_HOME the first entry says so and still stores null`() {
        load()

        assertEquals("JAVA_HOME: not set or not a JDK (Thoreau's JDK is used)", texts().first())
        assertNull(model.choices.first().path)
    }

    @Test
    fun `a project without a JDK shows the JAVA_HOME entry`() {
        load()

        assertEquals(model.choices.first(), model.selected.get())
    }

    @Test
    fun `the project's JDK is selected among the entries`() {
        val sdk = jdk(userHome.resolve(".sdkman/candidates/java/17.0.9-tem"), "17.0.9")
        settings.setJdk(project, sdk)

        load()

        assertEquals(sdk, model.selected.get()?.path)
        assertEquals(4, model.choices.size, "no entry is added for it")
    }

    @Test
    fun `a stored JDK that no entry lists is shown as its own entry before Other`() {
        val own = jdk(dir.resolve("own-jdk"), "22.0.1")
        settings.setJdk(project, own)

        load()

        assertEquals("22.0.1 ($own)", model.selected.get()?.text)
        assertEquals(own, model.selected.get()?.path)
        assertEquals("Other…", texts().last())
        assertTrue("22.0.1 ($own)" in texts())
    }

    @Test
    fun `a stored JDK that is gone is shown as not found and applying keeps it`() {
        val gone = dir.resolve("gone-jdk")
        settings.setJdk(project, gone)

        load()

        assertEquals("$gone (not found)", model.selected.get()?.text)
        model.apply(project)
        assertEquals(gone, settings.jdk(project))
    }

    @Test
    fun `apply stores the chosen JDK and JAVA_HOME removes the setting`() {
        val sdk = jdk(userHome.resolve(".sdkman/candidates/java/17.0.9-tem"), "17.0.9")
        load()

        model.selected.set(model.choices.single { it.path == sdk })
        model.apply(project)
        assertEquals(sdk, settings.jdk(project))

        model.selected.set(model.choices.first())
        model.apply(project)
        assertNull(settings.jdk(project))
    }

    @Test
    fun `apply before the entries are loaded changes nothing`() {
        val gone = dir.resolve("gone-jdk")
        settings.setJdk(project, gone)

        model.load(project)
        model.apply(project)

        assertEquals(gone, settings.jdk(project))
    }

    @Test
    fun `apply passes an IOException on`() {
        load()
        model.selected.set(model.choices.single { it.path == thoreau })
        store.update(project) { it.put("java", "not an object") }

        assertFailsWith<IOException> { model.apply(project) }
    }

    @Test
    fun `Other with a JDK folder adds it before Other and selects it`() {
        load()
        val picked = jdk(dir.resolve("picked-jdk"), "23.0.1")

        model.selected.set(model.choices.last())
        model.chooseOther { picked }
        run(ioQueue)
        run(fxQueue)

        assertEquals("23.0.1 ($picked)", model.selected.get()?.text)
        assertEquals(picked, model.selected.get()?.path)
        assertEquals("Other…", texts().last())
        assertEquals("", model.error.get())
    }

    @Test
    fun `Other with a folder that is no JDK shows the error and goes back to the previous entry`() {
        load()
        val previous = model.choices.single { it.path == thoreau }
        model.selected.set(previous)
        val notJdk = Files.createDirectories(dir.resolve("plain"))

        model.selected.set(model.choices.last())
        model.chooseOther { notJdk }
        run(ioQueue)
        run(fxQueue)

        assertEquals("$notJdk isn't a JDK: no bin/java or release file", model.error.get())
        assertEquals(previous, model.selected.get())
        assertEquals(3, model.choices.size)
    }

    @Test
    fun `a cancelled chooser goes back to the previous entry without an error`() {
        load()
        val previous = model.choices.first()
        model.selected.set(previous)

        model.selected.set(model.choices.last())
        model.chooseOther { null }

        assertEquals(previous, model.selected.get())
        assertEquals("", model.error.get())
    }

    @Test
    fun `choosing a real entry clears the error`() {
        load()
        model.selected.set(model.choices.first())
        model.selected.set(model.choices.last())
        model.chooseOther { dir.resolve("nothing") }
        run(ioQueue)
        run(fxQueue)
        assertTrue(model.error.get().isNotEmpty())

        model.selected.set(model.choices.single { it.path == thoreau })

        assertEquals("", model.error.get())
    }

    @Test
    fun `an older load does not overwrite a newer one`() {
        val sdk = jdk(userHome.resolve(".sdkman/candidates/java/17.0.9-tem"), "17.0.9")
        model.load(project)
        run(ioQueue) // this load saw no stored JDK; its result waits for the FX thread
        val older = ArrayDeque(fxQueue)
        fxQueue.clear()
        settings.setJdk(project, sdk)
        model.load(project)
        run(ioQueue)
        run(fxQueue)
        assertEquals(sdk, model.selected.get()?.path)

        run(older)

        assertEquals(sdk, model.selected.get()?.path)
    }

    @Test
    fun `loading again empties the entries, the choice and the error until the work is done`() {
        load()
        model.selected.set(model.choices.last())
        model.chooseOther { dir.resolve("nothing") }
        run(ioQueue)
        run(fxQueue)
        assertTrue(model.error.get().isNotEmpty())

        model.load(project)

        assertTrue(model.choices.isEmpty())
        assertNull(model.selected.get())
        assertEquals("", model.error.get())
    }

    @Test
    fun `apply with Other selected stores nothing`() {
        val sdk = jdk(userHome.resolve(".sdkman/candidates/java/17.0.9-tem"), "17.0.9")
        settings.setJdk(project, sdk)
        load()

        model.selected.set(model.choices.last())
        model.apply(project)

        assertEquals(sdk, settings.jdk(project))
    }

    @Test
    fun `newlines and markup in a JDK's path and a hostile release file stay on one short line`() {
        val nasty = jdk(userHome.resolve(".jdks/jdk\n<b>x<i>\u0007"), "21.0.1")
        val huge = userHome.resolve(".jdks/huge")
        huge
            .resolve("bin")
            .createDirectories()
            .resolve("java")
            .writeText("#!/bin/sh\n")
        huge.resolve("release").writeBytes("JAVA_VERSION=\"21.0.1-${"x".repeat(60_000)}\"\n".toByteArray())
        val binary = userHome.resolve(".jdks/binary")
        binary
            .resolve("bin")
            .createDirectories()
            .resolve("java")
            .writeText("#!/bin/sh\n")
        binary.resolve("release").writeBytes(byteArrayOf(0, 1, -1, -2, 10, 13))

        load()

        assertTrue(texts().none { '\n' in it || '\r' in it || '\u0007' in it }, texts().toString())
        assertTrue(texts().all { it.length < 600 }, texts().map { it.length }.toString())
        assertTrue(texts().any { "<b>x<i>" in it }, "markup is kept as plain text")
        assertEquals(nasty, model.choices.single { it.path == nasty }.path, "the stored value is the real path")
    }

    @Test
    fun `a stored path that is not normalised selects the entry of the same folder`() {
        val sdk = jdk(userHome.resolve(".sdkman/candidates/java/17"), "17.0.9")
        settings.setJdk(project, userHome.resolve(".sdkman/candidates/java/x/../17"))

        load()

        assertEquals(sdk, model.selected.get()?.path)
        assertEquals(4, model.choices.size, "no entry is added for the other spelling")
        model.apply(project)
        assertEquals(sdk, settings.jdk(project)?.normalize())
    }

    @Test
    fun `a folder picked in Other that an entry lists in another spelling selects that entry`() {
        val sdk = jdk(userHome.resolve(".sdkman/candidates/java/17"), "17.0.9")
        load()

        model.selected.set(model.choices.last())
        model.chooseOther { userHome.resolve(".sdkman/candidates/java/./17") }
        run(ioQueue)
        run(fxQueue)

        assertEquals(sdk, model.selected.get()?.path)
        assertEquals(4, model.choices.size)
    }

    @Test
    fun `a folder picked before the page was loaded again is dropped`() {
        load()
        val picked = jdk(dir.resolve("picked-jdk"), "23.0.1")
        model.selected.set(model.choices.last())
        model.chooseOther { picked }
        model.load(project) // the page opened again while the folder is being read

        run(ioQueue)
        run(fxQueue)

        assertTrue(model.choices.isNotEmpty(), "the second load's result")
        assertTrue(model.choices.none { it.path == picked })
        assertEquals(model.choices.first(), model.selected.get())
    }

    @Test
    fun `a folder read while the user picked another entry does not override that entry`() {
        load()
        val picked = jdk(dir.resolve("picked-jdk"), "23.0.1")
        model.selected.set(model.choices.last())
        model.chooseOther { picked }
        val entry = model.choices.single { it.path == thoreau }
        model.selected.set(entry)

        run(ioQueue)
        run(fxQueue)

        assertEquals(entry, model.selected.get())
        assertTrue(model.choices.none { it.path == picked })
    }

    @Test
    fun `a failing look for JDKs ends loading and shows the error`() {
        shellFailure = IllegalStateException("boom")

        load()

        assertFalse(model.loading.get())
        assertTrue("boom" in model.error.get(), model.error.get())
    }

    @Test
    fun `apply stores nothing while the choice is the loaded one`() {
        val own = jdk(dir.resolve("own-jdk"), "22.0.1")
        var changes = 0
        settings.onChange { changes++ }
        load()
        model.apply(project)
        assertEquals(0, changes, "a project without a JDK stays without a setting")
        assertNull(store.read(project))

        settings.setJdk(project, own.resolve("../own-jdk"))
        changes = 0
        load()
        model.apply(project)
        assertEquals(0, changes, "the stored JDK, however spelled, is not stored again")

        model.selected.set(model.choices.first())
        model.apply(project)
        assertEquals(1, changes)
    }
}
