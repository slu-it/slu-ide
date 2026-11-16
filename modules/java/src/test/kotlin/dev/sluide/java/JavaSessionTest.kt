package dev.sluide.java

import dev.sluide.languageservers.LspUris
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
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
    private val closed = AtomicInteger()
    private val lombokChecks = mutableListOf<Path>()
    private var lombokRoots = setOf<Path>()

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
            Runnable::run,
            DEBOUNCE_MILLIS,
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
    fun `it takes the notifications the import tracking will read`() {
        assertEquals(setOf("language/status", "language/eventNotification"), session.notifications)
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
        session.buildFileChanged(build.resolve("build.gradle.kts"))
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
