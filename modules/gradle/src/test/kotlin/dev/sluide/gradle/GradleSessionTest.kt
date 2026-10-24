package dev.sluide.gradle

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.gradle.tasks.GradleProject
import dev.sluide.gradle.tasks.TaskModel
import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.projecttrust.TrustedProjects
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import dev.sluide.terminal.Terminals
import dev.sluide.terminal.settings.TerminalScrollSettings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GradleSessionTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { SettingsStore(dir.resolve("settings/settings.properties")) }
    private val globalChecks = AtomicInteger()
    private val session by lazy {
        GradleSession(
            ProjectTrust(
                emptyList(),
                TrustedProjects(dir.resolve("settings/trust.properties")),
                BackgroundTasks.forTests(),
            ),
            BackgroundTasks.forTests(),
            Terminals(FontSettings(store, FontSettings.systemDefaults()), TerminalScrollSettings(store)),
        ) {
            globalChecks.incrementAndGet()
            true
        }
    }

    @AfterEach
    fun close() = session.close()

    /** A build in [root] with a root project and `:app`, each with a build file of [name]. */
    private fun model(
        root: Path,
        name: String = "build.gradle.kts",
    ): TaskModel {
        root.resolve("app").createDirectories()
        val rootBuild = root.resolve(name).apply { writeText("") }
        val appBuild = root.resolve("app/$name").apply { writeText("") }
        return TaskModel(
            root,
            listOf(
                GradleProject(":", "root", root, rootBuild, emptyList()),
                GradleProject(":app", "app", root.resolve("app"), appBuild, emptyList()),
            ),
        )
    }

    @Test
    fun `the global check runs once, for the first caller, whatever the number of callers`() {
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val results = (1..4).map { pool.submit<Boolean> { start.await().let { session.globalAvailable() } } }
        start.countDown()
        assertTrue(results.all { it.get(10, TimeUnit.SECONDS) })
        assertTrue(session.globalAvailable())
        assertEquals(1, globalChecks.get())
        pool.shutdown()
    }

    @Test
    fun `a build file finds the loaded build it belongs to by real path, also through a link`() {
        val root = dir.resolve("work/fx")
        val build = session.keep(model(root))
        val link = Files.createSymbolicLink(dir.resolve("link"), root)

        assertSame(build, session.modelFor(root.resolve("app/build.gradle.kts")))
        assertSame(build, session.modelFor(link.resolve("app/build.gradle.kts")))
        assertSame(build, session.modelFor(root.resolve("build.gradle.kts")))
        assertNull(session.modelFor(dir.resolve("other/build.gradle.kts")))
        assertSame(build, session.build(root.toRealPath()))
    }

    @Test
    fun `storing a build again replaces it, and every listener hears of it`() {
        val root = dir.resolve("fx")
        var changes = 0
        session.onChange { changes++ }
        val first = session.keep(model(root))
        val second = session.keep(model(root))

        assertSame(second, session.modelFor(root.resolve("build.gradle.kts")))
        assertEquals(first.key, second.key)
        assertEquals(2, changes)
    }

    @ParameterizedTest
    @CsvSource(
        "build.gradle.kts, true",
        "app/build.gradle, true",
        "settings.gradle, true",
        "settings.gradle.kts, true",
        "gradle.properties, true",
        "gradle/libs.versions.toml, true",
        "buildSrc/src/main/kotlin/conventions.gradle.kts, true",
        "src/Main.kt, false",
        "notes.md, false",
    )
    fun `saving a build file under a loaded build marks it stale, once, other files do not`(
        file: String,
        stale: Boolean,
    ) {
        val root = dir.resolve("fx")
        val build = session.keep(model(root))
        var changes = 0
        session.onChange { changes++ }

        session.markStale(root.resolve(file))
        session.markStale(root.resolve(file))

        assertEquals(stale, checkNotNull(session.build(build.key)).stale)
        assertEquals(if (stale) 1 else 0, changes)
    }

    @Test
    fun `a build file outside every loaded build marks nothing, and loading again clears the stale mark`() {
        val root = dir.resolve("fx")
        val build = session.keep(model(root))
        var changes = 0
        val subscription = session.onChange { changes++ }

        session.markStale(dir.resolve("other/build.gradle"))
        assertFalse(checkNotNull(session.build(build.key)).stale)

        session.markStale(root.resolve("app/build.gradle.kts"))
        session.keep(model(root))
        assertFalse(checkNotNull(session.build(build.key)).stale)
        assertEquals(2, changes)

        subscription.close()
        session.markStale(root.resolve("app/build.gradle.kts"))
        assertEquals(2, changes, "no longer told")
    }

    @Test
    fun `fileSaved marks the build stale on the session's thread`() {
        val root = dir.resolve("fx")
        val build = session.keep(model(root))
        val threads = mutableListOf<String>()
        val told = CountDownLatch(1)
        session.onChange {
            threads += Thread.currentThread().name
            told.countDown()
        }

        session.fileSaved(root.resolve("gradle.properties"))

        assertTrue(told.await(10, TimeUnit.SECONDS))
        assertTrue(checkNotNull(session.build(build.key)).stale)
        assertEquals(listOf("Gradle"), threads)
    }

    @Test
    fun `one load per key at a time, its end tells the views and whether it failed`() {
        val key = dir.resolve("fx")
        var changes = 0
        session.onChange { changes++ }

        assertTrue(session.claimLoad(key))
        assertFalse(session.claimLoad(key), "a second view waits")
        assertTrue(session.isLoading(key))
        session.loadEnded(key, failed = true)
        assertFalse(session.isLoading(key))
        assertTrue(session.loadFailed(key))
        assertEquals(1, changes)

        assertTrue(session.claimLoad(key), "Refresh loads again")
        assertFalse(session.loadFailed(key))
        session.loadEnded(key, failed = false)
        assertFalse(session.loadFailed(key))
        assertTrue(session.claimLoad(dir.resolve("other")), "other builds load at the same time")
    }

    @Test
    fun `the init script is extracted once and deleted with the session`() {
        val script = session.initScript()
        val list = session.taskListFile()

        assertSame(script, session.initScript())
        assertTrue(script.exists() && list.exists())
        session.close()
        assertFalse(script.exists() || list.exists() || script.parent.exists())
    }
}
