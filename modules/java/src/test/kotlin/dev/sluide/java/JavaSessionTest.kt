package dev.sluide.java

import com.google.gson.JsonParser
import dev.sluide.languageservers.LspUris
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaSessionTest {
    @TempDir
    lateinit var dir: Path

    private val window: Path by lazy {
        dir.resolve("work").also {
            java.nio.file.Files
                .createDirectories(it)
        }
    }
    private val access = RecordingAccess()
    private val state = JavaWindowState()
    private val worker = InlineExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    private val projectSettings by lazy {
        JavaProjectSettings(ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())) {
            it.run()
        }
    }
    private val lombokJar: Path by lazy { dir.resolve("lombok.jar") }
    private var jarAvailable = true
    private val leaseClosed = AtomicInteger()
    private val tickerStops = AtomicInteger()
    private val closed = AtomicInteger()
    private val lombokChecks = mutableListOf<Path>()
    private var lombokRoots = setOf<Path>()

    /** Whether the session's dispatch to the FX thread waits in [dispatched] instead of running at once. */
    @Volatile private var deferDispatch = false
    private val dispatched = CopyOnWriteArrayList<Runnable>()

    private val imports by lazy {
        JavaImports(
            access,
            state.imports,
            worker,
            Runnable::run,
            Clock.systemUTC(),
            { AutoCloseable { tickerStops.incrementAndGet() } },
            { null },
            { emptyList() },
        )
    }

    private val session: JavaSession by lazy {
        JavaSession(
            window,
            access,
            state,
            { mapOf("jdk" to projectSettings.jdk(window)?.toString()) },
            { lombokJar.takeIf { jarAvailable } },
            { root ->
                lombokChecks.add(root)
                root in lombokRoots
            },
            projectSettings,
            { leaseClosed.incrementAndGet() },
            worker,
            scheduler,
            { task -> if (deferDispatch) dispatched.add(task) else task.run() },
            DEBOUNCE_MILLIS,
            imports,
            { closed.incrementAndGet() },
        )
    }

    @AfterEach
    fun stop() {
        scheduler.shutdownNow()
    }

    private fun root(name: String): Path =
        window.resolve(name).also {
            java.nio.file.Files
                .createDirectories(it)
        }

    @Test
    fun `it takes jdtls's status and event notifications`() {
        assertEquals(setOf("language/status", "language/eventNotification"), session.notifications)
    }

    @Test
    fun `announcing and removing a folder starts and drops its import state`() {
        val a = root("a")

        session.onFolderAdded(a)
        assertTrue(state.imports[a] is ImportState.Importing)

        session.onFolderRemoved(a)

        assertEquals(null, state.imports[a])
    }

    @Test
    fun `a refresh reaches the import tracking as a new announcement of the root`() {
        val a = root("a")
        session.onFolderAdded(a)
        state.imports[a] = ImportState.Failed("x", emptyList())

        session.onFolderRefreshed(a)

        assertTrue(state.imports[a] is ImportState.Importing)
    }

    @Test
    fun `progress reaches the import tracking`() {
        val a = root("a")
        session.onFolderAdded(a)
        session.onNotification(
            "language/status",
            JsonParser.parseString(
                """{"type":"Message","message":"Updating workspace folders: Adding 1 folder(s), removing 0 folders."}""",
            ),
        )

        session.onProgress("t", JsonParser.parseString("""{"kind":"begin","title":"Updating workspace folders"}"""))
        session.onProgress("t", JsonParser.parseString("""{"kind":"end"}"""))

        // RecordingAccess answers getAll with nothing: the root has no project.
        assertTrue(state.imports[a] is ImportState.Failed)
    }

    @Test
    fun `an event payload sent as language status is not taken as an event`() {
        val a = root("a")
        session.onFolderAdded(a)
        val event = """{"eventType":300,"data":{"projectUri":"file://$a/","highestJavaVersion":"24"}}"""

        session.onNotification("language/status", JsonParser.parseString(event))
        assertTrue(state.imports[a] is ImportState.Importing)
        session.onNotification("language/eventNotification", JsonParser.parseString(event))

        assertTrue(state.imports[a] is ImportState.Failed)
    }

    @Test
    fun `a new connection reaches the import tracking`() {
        val a = root("a")
        session.onNotification("language/status", folderStatus(0, 1))

        session.onConnected()
        session.onFolderAdded(a)
        session.onNotification("language/status", folderStatus(1, 0))
        session.onProgress("t", JsonParser.parseString("""{"kind":"begin","title":"Updating workspace folders"}"""))
        session.onProgress("t", JsonParser.parseString("""{"kind":"end"}"""))

        // RecordingAccess answers getAll with nothing: the import ended without a project.
        assertTrue(state.imports[a] is ImportState.Failed)
    }

    private fun folderStatus(
        added: Int,
        removed: Int,
    ) = JsonParser.parseString(
        """{"type":"Message","message":"Updating workspace folders: Adding $added folder(s), removing $removed folders."}""",
    )

    @Test
    fun `a stopped server clears the import states`() {
        session.onFolderAdded(root("a"))
        assertTrue(state.imports.isNotEmpty())

        session.onStopped()

        assertTrue(state.imports.isEmpty())
    }

    @Test
    fun `closing the session stops the import ticker`() {
        session.onFolderAdded(root("a"))

        session.close()

        assertEquals(1, tickerStops.get())
    }

    @Test
    fun `a build that uses Lombok restarts the server once and sets the flag the command reads`() {
        val first = root("a")
        val second = root("b")
        lombokRoots = setOf(first, second)

        session.onFolderAdded(first)
        session.onFolderAdded(second)

        assertEquals(1, access.restarts.size)
        assertTrue(state.lombokRestarted.get())
        assertEquals(listOf(first), lombokChecks, "after the flag is set the other roots are not even looked at")
    }

    @Test
    fun `a refresh of a root whose build now uses Lombok restarts the server once`() {
        val root = root("a")
        session.onFolderAdded(root)
        assertEquals(0, access.restarts.size)
        lombokRoots = setOf(root)

        session.onFolderRefreshed(root)

        assertEquals(1, access.restarts.size)
        assertEquals(listOf(root, root), lombokChecks)
    }

    @Test
    fun `a restarted server announcing the root again does not restart it again`() {
        val first = root("a")
        lombokRoots = setOf(first)
        session.onFolderAdded(first)

        session.onFolderAdded(first)

        assertEquals(1, access.restarts.size)
    }

    @Test
    fun `a build without Lombok does not restart and leaves the flag`() {
        session.onFolderAdded(root("a"))

        assertEquals(0, access.restarts.size)
        assertFalse(state.lombokRestarted.get())
    }

    @Test
    fun `without an installed Lombok jar nothing restarts and the flag stays unset for a later install`() {
        val first = root("a")
        lombokRoots = setOf(first)
        jarAvailable = false
        session.onFolderAdded(first)

        assertEquals(0, access.restarts.size)
        assertFalse(state.lombokRestarted.get())

        jarAvailable = true
        session.onFolderAdded(first)
        assertEquals(1, access.restarts.size)
    }

    @Test
    fun `the Lombok check runs on the worker, not on the calling thread`() {
        val queued = mutableListOf<Runnable>()
        val deferred =
            JavaSession(
                window,
                access,
                state,
                { emptyMap() },
                { lombokJar },
                { true },
                projectSettings,
                {},
                InlineExecutor(),
                scheduler,
                { queued += it },
                DEBOUNCE_MILLIS,
                imports,
                {},
            )

        deferred.onFolderAdded(root("a"))

        assertEquals(0, access.restarts.size, "the restart waits for the dispatch back to the FX thread")
        assertEquals(1, queued.size)
        queued.single().run()
        assertEquals(1, access.restarts.size)
    }

    @Test
    fun `a Lombok answer that comes after the session closed restarts nothing`() {
        val queued = mutableListOf<Runnable>()
        val deferred =
            JavaSession(
                window,
                access,
                state,
                { emptyMap() },
                { lombokJar },
                { true },
                projectSettings,
                {},
                InlineExecutor(),
                scheduler,
                { queued += it },
                DEBOUNCE_MILLIS,
                imports,
                {},
            )
        deferred.onFolderAdded(root("a"))

        deferred.close()
        queued.forEach { it.run() }

        assertEquals(0, access.restarts.size)
        assertFalse(state.lombokRestarted.get())
    }

    @Test
    fun `calls after close do nothing and do not fail`() {
        val gradle = root("g")
        gradle.resolve("settings.gradle").file()
        val lombok = root("lombok")
        lombokRoots = setOf(lombok)
        session.onFolderAdded(gradle)
        session.close()

        session.onFolderAdded(lombok)
        session.onFolderRefreshed(lombok)
        session.buildFileChanged(gradle.resolve("settings.gradle"))

        Thread.sleep(DEBOUNCE_MILLIS * 3) // A notification that must not come gets three debounces.
        assertTrue(access.notifications.isEmpty())
        assertEquals(0, access.restarts.size)
    }

    @Test
    fun `a stopped server forgets the roots, the next one announces them again`() {
        val gradle = root("g")
        gradle.resolve("settings.gradle").file()
        session.onFolderAdded(gradle)
        session.onStopped()

        session.buildFileChanged(gradle.resolve("settings.gradle"))

        Thread.sleep(DEBOUNCE_MILLIS * 3) // No signal for a notification that must not come.
        assertTrue(access.notifications.isEmpty())
        session.onFolderAdded(gradle)
        session.buildFileChanged(gradle.resolve("settings.gradle"))
        awaitCondition("the notification") { access.notifications.isNotEmpty() }
    }

    @Test
    fun `a JDK change of the window's project sends the new settings`() {
        session
        val jdk = dir.resolve("jdk-21")

        projectSettings.setJdk(window, jdk)

        assertEquals(listOf<Any>(mapOf("jdk" to jdk.toString())), access.configurations)
    }

    @Test
    fun `a JDK change refreshes the roots whose import failed, after the settings went out`() {
        session
        val failed = root("failed")
        state.imports[failed] = ImportState.Failed("x", emptyList())
        state.imports[root("done")] = ImportState.Done(null)
        state.imports[root("busy")] = ImportState.Importing(java.time.Instant.EPOCH)

        projectSettings.setJdk(window, dir.resolve("jdk-21"))

        assertEquals(listOf("configuration", "refresh $failed"), access.sent)
    }

    @Test
    fun `a build file change below a failed root refreshes it once and sends no update`() {
        val failed = root("g")
        failed.resolve("settings.gradle").file()
        session.onFolderAdded(failed)
        state.imports[failed] = ImportState.Failed("x", emptyList())

        repeat(3) { session.buildFileChanged(failed.resolve("pom.xml")) }
        session.buildFileChanged(failed.resolve("settings.gradle"))

        awaitCondition("the refresh") { access.refreshed.isNotEmpty() }
        Thread.sleep(DEBOUNCE_MILLIS * 3) // Nothing more may come: the changes merge into one refresh.
        assertEquals(listOf(failed), access.refreshed)
        assertTrue(access.notifications.isEmpty())
    }

    @Test
    fun `a change of a build file jdtls watches, below a root that did not fail, neither refreshes nor notifies`() {
        val gradle = root("g")
        gradle.resolve("build.gradle").file()
        session.onFolderAdded(gradle)
        state.imports[gradle] = ImportState.Done(null)

        session.buildFileChanged(gradle.resolve("build.gradle"))

        Thread.sleep(DEBOUNCE_MILLIS * 3) // Past the debounce; nothing may come.
        assertTrue(access.refreshed.isEmpty())
        assertTrue(access.notifications.isEmpty())
    }

    @Test
    fun `a root that is no longer failed when the debounce passes is not refreshed`() {
        val maven = root("m")
        session.onFolderAdded(maven)
        state.imports[maven] = ImportState.Failed("x", emptyList())

        session.buildFileChanged(maven.resolve("pom.xml"))
        state.imports[maven] = ImportState.Importing(java.time.Instant.EPOCH)

        Thread.sleep(DEBOUNCE_MILLIS * 3) // Past the debounce; nothing may come.
        assertTrue(access.refreshed.isEmpty())
    }

    @Test
    fun `a root that imports again by the time the refresh reaches the FX thread is not refreshed`() {
        val maven = root("m")
        session.onFolderAdded(maven)
        state.imports[maven] = ImportState.Failed("x", emptyList())
        deferDispatch = true

        session.buildFileChanged(maven.resolve("pom.xml"))
        awaitCondition("the debounce passed") { dispatched.isNotEmpty() }
        // The user clicked Retry Import meanwhile.
        state.imports[maven] = ImportState.Importing(java.time.Instant.EPOCH)
        dispatched.forEach(Runnable::run)

        assertTrue(access.refreshed.isEmpty())
    }

    @Test
    fun `a refresh that reaches the FX thread after the session closed is not sent`() {
        val maven = root("m")
        session.onFolderAdded(maven)
        state.imports[maven] = ImportState.Failed("x", emptyList())
        deferDispatch = true

        session.buildFileChanged(maven.resolve("pom.xml"))
        awaitCondition("the debounce passed") { dispatched.isNotEmpty() }
        session.close()
        dispatched.forEach(Runnable::run)

        assertTrue(access.refreshed.isEmpty())
    }

    @Test
    fun `a JDK change whose settings were resolved when the session closed is not sent and refreshes nothing`() {
        val queued = mutableListOf<Runnable>()
        val deferred =
            JavaSession(
                window,
                access,
                state,
                { mapOf("jdk" to "21") },
                { lombokJar },
                { false },
                projectSettings,
                {},
                InlineExecutor(),
                scheduler,
                { queued += it },
                DEBOUNCE_MILLIS,
                imports,
                {},
            )
        state.imports[root("failed")] = ImportState.Failed("x", emptyList())
        projectSettings.setJdk(window, dir.resolve("jdk-21"))
        assertEquals(1, queued.size)

        deferred.close()
        queued.forEach { it.run() }

        assertTrue(access.configurations.isEmpty())
        assertTrue(access.refreshed.isEmpty())
    }

    @Test
    fun `a JDK change of another project is not sent`() {
        session

        projectSettings.setJdk(dir.resolve("other"), dir.resolve("jdk-21"))

        assertTrue(access.configurations.isEmpty())
    }

    @Test
    fun `a JDK change after close is not sent`() {
        session.close()

        projectSettings.setJdk(window, dir.resolve("jdk-21"))

        assertTrue(access.configurations.isEmpty())
    }

    @Test
    fun `a build file change sends one projectConfigurationUpdate with the settings file's URI after the debounce`() {
        val gradle = root("g")
        gradle.resolve("settings.gradle").file()
        gradle.resolve("build.gradle").file()
        session.onFolderAdded(gradle)

        repeat(3) { session.buildFileChanged(gradle.resolve("settings.gradle")) }

        awaitCondition("the notification") { access.notifications.isNotEmpty() }
        // Nothing more may come: a second notification would be a debounce that did not merge the three changes.
        Thread.sleep(DEBOUNCE_MILLIS * 3)
        assertEquals(
            listOf<Pair<String, Any?>>(
                "java/projectConfigurationUpdate" to mapOf("uri" to LspUris.of(gradle.resolve("settings.gradle"))),
            ),
            access.notifications,
        )
    }

    @Test
    fun `nothing is sent before the debounce passed`() {
        val gradle = root("g")
        gradle.resolve("settings.gradle").file()
        val slow =
            JavaSession(
                window,
                access,
                state,
                { emptyMap() },
                { null },
                { false },
                projectSettings,
                {},
                worker,
                scheduler,
                Runnable::run,
                60_000,
                imports,
                {},
            )
        slow.onFolderAdded(gradle)

        slow.buildFileChanged(gradle.resolve("settings.gradle"))

        assertTrue(access.notifications.isEmpty())
    }

    @Test
    fun `the Kotlin script and the build file stand in when there is no settings file`() {
        val kts = root("k")
        kts.resolve("settings.gradle.kts").file()
        val build = root("b")
        build.resolve("build.gradle.kts").file()
        val plain = root("p")
        plain.resolve("build.gradle").file()
        listOf(kts, build, plain).forEach(session::onFolderAdded)

        session.buildFileChanged(kts.resolve("gradle/wrapper/gradle-wrapper.properties"))
        session.buildFileChanged(build.resolve("gradle/wrapper/gradle-wrapper.properties"))
        session.buildFileChanged(plain.resolve("gradle/wrapper/gradle-wrapper.properties"))

        awaitCondition("three notifications") { access.notifications.size == 3 }
        assertEquals(
            setOf(
                LspUris.of(kts.resolve("settings.gradle.kts")),
                LspUris.of(build.resolve("build.gradle.kts")),
                LspUris.of(plain.resolve("build.gradle")),
            ),
            access.notifications.map { (it.second as Map<*, *>)["uri"] }.toSet(),
        )
    }

    @Test
    fun `a build without a gradle file gets no notification`() {
        val maven = root("m")
        maven.resolve("pom.xml").file()
        session.onFolderAdded(maven)

        session.buildFileChanged(maven.resolve("gradle/wrapper/gradle-wrapper.properties"))

        // A negative check without a signal: three debounces.
        Thread.sleep(DEBOUNCE_MILLIS * 3)
        assertTrue(access.notifications.isEmpty())
    }

    @Test
    fun `a change outside every announced root is ignored and the deepest root wins`() {
        val outer = root("outer")
        outer.resolve("settings.gradle").file()
        val inner = outer.resolve("inner")
        inner.resolve("settings.gradle").file()
        session.onFolderAdded(outer)
        session.onFolderAdded(inner)

        session.buildFileChanged(window.resolve("elsewhere/settings.gradle"))
        session.buildFileChanged(inner.resolve("gradle/wrapper/gradle-wrapper.properties"))

        awaitCondition("the notification") { access.notifications.isNotEmpty() }
        Thread.sleep(DEBOUNCE_MILLIS * 3) // Nothing else may follow.
        assertEquals(
            listOf<Any?>(mapOf("uri" to LspUris.of(inner.resolve("settings.gradle")))),
            access.notifications.map { it.second },
        )
    }

    @Test
    fun `a removed root is not notified about any more`() {
        val gradle = root("g")
        gradle.resolve("settings.gradle").file()
        session.onFolderAdded(gradle)
        session.onFolderRemoved(gradle)

        session.buildFileChanged(gradle.resolve("settings.gradle"))

        Thread.sleep(DEBOUNCE_MILLIS * 3) // No signal for a notification that must not come.
        assertTrue(access.notifications.isEmpty())
    }

    @Test
    fun `changes of different roots are debounced apart`() {
        val one = root("one")
        one.resolve("settings.gradle").file()
        val two = root("two")
        two.resolve("settings.gradle").file()
        session.onFolderAdded(one)
        session.onFolderAdded(two)

        session.buildFileChanged(one.resolve("settings.gradle"))
        session.buildFileChanged(two.resolve("settings.gradle"))

        awaitCondition("two notifications") { access.notifications.size == 2 }
    }

    @Test
    fun `closing releases the workspace use once, tells the spec and drops a pending notification`() {
        val gradle = root("g")
        gradle.resolve("settings.gradle").file()
        val slow =
            JavaSession(
                window,
                access,
                state,
                { emptyMap() },
                { null },
                { false },
                projectSettings,
                { leaseClosed.incrementAndGet() },
                worker,
                scheduler,
                Runnable::run,
                DEBOUNCE_MILLIS * 3,
                imports,
                { closed.incrementAndGet() },
            )
        slow.onFolderAdded(gradle)
        slow.buildFileChanged(gradle.resolve("settings.gradle"))

        slow.close()
        slow.close()

        assertEquals(1, leaseClosed.get())
        assertEquals(1, closed.get())
        assertTrue(worker.isShutdown)
        assertTrue(scheduler.isShutdown)
        Thread.sleep(DEBOUNCE_MILLIS * 5) // Past the pending notification's time.
        assertTrue(access.notifications.isEmpty())
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 200L
    }
}
