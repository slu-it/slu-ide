package dev.sluide.java

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaHomesTest {
    @TempDir
    lateinit var root: Path

    private fun jdk(
        name: String,
        version: String = "21.0.1",
    ): Path {
        val home = root.resolve(name)
        home
            .resolve("bin")
            .createDirectories()
            .resolve("java")
            .writeText("")
        home.resolve("release").writeText("JAVA_VERSION=\"$version\"\n")
        return home
    }

    private fun notJdk(name: String): Path = root.resolve(name).createDirectories()

    private fun homes(
        shell: (String, Map<String, String>) -> String? = { _, _ -> null },
        environment: Map<String, String> = emptyMap(),
        thoreau: Path = jdk("thoreau", "25"),
    ) = JavaHomes(shell, environment, thoreau)

    @Test
    fun `the shell prints JAVA_HOME with a script that quotes it`() {
        var script = ""
        var env: Map<String, String>? = null
        val homes =
            homes(
                shell = { s, e ->
                    script = s
                    env = e
                    ""
                },
                environment = mapOf("X" to "y"),
            )
        homes.loginShellJavaHome()
        assertEquals("printf '%s' \"\$JAVA_HOME\"", script)
        assertEquals(mapOf("X" to "y"), env)
    }

    @Test
    fun `the login shell's JAVA_HOME is used`() {
        val shellJdk = jdk("shell", "17.0.2")
        val homes =
            homes(
                shell = { _, _ -> shellJdk.toString() },
                environment =
                    mapOf("JAVA_HOME" to jdk("env").toString()),
            )
        assertEquals(shellJdk, homes.loginShellJavaHome())
        assertEquals(ProjectJdk(Jdk(shellJdk, "17.0.2", 17), JdkSource.LOGIN_SHELL, null), homes.resolve(null))
    }

    @Test
    fun `the shell is asked once for several calls`() {
        val asked = AtomicInteger()
        val homes =
            homes(shell = { _, _ ->
                asked.incrementAndGet()
                null
            })
        repeat(3) { assertNull(homes.loginShellJavaHome()) }
        homes.resolve(null)
        assertEquals(1, asked.get())
    }

    @Test
    fun `an answer lost to an interrupted thread is asked again`() {
        val answers = ArrayDeque(listOf<String?>(null, "/x"))
        val asked = AtomicInteger()
        val homes =
            homes(shell = { _, _ ->
                asked.incrementAndGet()
                answers.removeFirst()
            })
        Thread.currentThread().interrupt()
        try {
            assertNull(homes.loginShellJavaHome())
            assertTrue(Thread.currentThread().isInterrupted, "the flag is kept")
        } finally {
            Thread.interrupted()
        }
        assertEquals(Path.of("/x"), homes.loginShellJavaHome())
        assertEquals(Path.of("/x"), homes.loginShellJavaHome())
        assertEquals(2, asked.get())
    }

    @Test
    fun `a real answer on an interrupted thread is kept`() {
        val asked = AtomicInteger()
        val homes =
            homes(shell = { _, _ ->
                asked.incrementAndGet()
                "/x"
            })
        Thread.currentThread().interrupt()
        try {
            assertEquals(Path.of("/x"), homes.loginShellJavaHome())
        } finally {
            Thread.interrupted()
        }
        assertEquals(Path.of("/x"), homes.loginShellJavaHome())
        assertEquals(1, asked.get())
    }

    @Test
    fun `two threads asking at once run the shell once`() {
        val asked = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val homes =
            homes(shell = { _, _ ->
                asked.incrementAndGet()
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                "/x"
            })
        val results = java.util.concurrent.ConcurrentLinkedQueue<Path?>()
        val first = Thread { results += homes.loginShellJavaHome() }.apply { start() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val second = Thread { results += homes.loginShellJavaHome() }.apply { start() }
        // The second thread must be waiting inside the call or not have entered it yet; either way the shell stays at one.
        second.join(200)
        release.countDown()
        first.join(10_000)
        second.join(10_000)
        assertEquals(1, asked.get())
        assertEquals(listOf<Path?>(Path.of("/x"), Path.of("/x")), results.toList())
    }

    @Test
    fun `a blank shell answer falls to the environment`() {
        val env = jdk("env", "11.0.1")
        val homes = homes(shell = { _, _ -> "  \n" }, environment = mapOf("JAVA_HOME" to env.toString()))
        assertNull(homes.loginShellJavaHome())
        assertEquals(ProjectJdk(Jdk(env, "11.0.1", 11), JdkSource.ENVIRONMENT, null), homes.resolve(null))
    }

    @Test
    fun `a shell answer is trimmed`() {
        val shellJdk = jdk("shell")
        assertEquals(shellJdk, homes(shell = { _, _ -> "$shellJdk\n" }).loginShellJavaHome())
    }

    @Test
    fun `a valid project setting wins`() {
        val project = jdk("project", "8")
        val result = homes(shell = { _, _ -> jdk("shell").toString() }).resolve(project)
        assertEquals(ProjectJdk(Jdk(project, "8", 8), JdkSource.PROJECT_SETTING, null), result)
    }

    @Test
    fun `an invalid project setting names itself and falls back`() {
        val bad = notJdk("bad")
        val shellJdk = jdk("shell")
        val result = homes(shell = { _, _ -> shellJdk.toString() }).resolve(bad)
        assertEquals(JdkSource.LOGIN_SHELL, result?.source)
        assertEquals(
            "The project's JDK $bad is not a JDK (no bin/java or release file); using the login shell's JAVA_HOME.",
            result?.problem,
        )
    }

    @Test
    fun `an invalid login shell JAVA_HOME is skipped with a problem naming it`() {
        val bad = notJdk("bad")
        val env = jdk("env")
        val result =
            homes(shell = {
                _,
                _,
                ->
                bad.toString()
            }, environment = mapOf("JAVA_HOME" to env.toString())).resolve(null)
        assertEquals(JdkSource.ENVIRONMENT, result?.source)
        assertTrue(result?.problem?.contains(bad.toString()) == true, result?.problem)
        assertTrue(result?.problem?.contains("login shell") == true, result?.problem)
    }

    @Test
    fun `an invalid environment JAVA_HOME is skipped with a problem naming it`() {
        val bad = notJdk("bad")
        val thoreau = jdk("thoreau", "25")
        val result = homes(environment = mapOf("JAVA_HOME" to bad.toString()), thoreau = thoreau).resolve(null)
        assertEquals(JdkSource.THOREAU, result?.source)
        assertTrue(result?.problem?.contains(bad.toString()) == true, result?.problem)
    }

    @Test
    fun `the first problem is the one kept`() {
        val badProject = notJdk("badp")
        val badEnv = notJdk("bade")
        val result = homes(environment = mapOf("JAVA_HOME" to badEnv.toString())).resolve(badProject)
        assertEquals(JdkSource.THOREAU, result?.source)
        assertTrue(result?.problem?.startsWith("The project's JDK $badProject") == true, result?.problem)
        assertTrue(result?.problem?.contains(badEnv.toString()) == false, result?.problem)
    }

    @Test
    fun `everything invalid but Thoreau's own JDK gives Thoreau's`() {
        val thoreau = jdk("thoreau", "25")
        val result =
            homes(shell = {
                _,
                _,
                ->
                "/nope"
            }, environment = mapOf("JAVA_HOME" to "/nope2"), thoreau = thoreau).resolve(notJdk("bad"))
        assertEquals(Jdk(thoreau, "25", 25), result?.jdk)
        assertEquals(JdkSource.THOREAU, result?.source)
    }

    @Test
    fun `nothing valid gives null`() {
        assertNull(homes(thoreau = notJdk("thoreau")).resolve(null))
    }

    @Test
    fun `a blank environment JAVA_HOME is not set`() {
        val result = homes(environment = mapOf("JAVA_HOME" to "  ")).resolve(null)
        assertEquals(JdkSource.THOREAU, result?.source)
        assertNull(result?.problem)
    }

    @Test
    fun `no JAVA_HOME anywhere gives Thoreau's JDK without a problem`() {
        val result = homes().resolve(null)
        assertEquals(JdkSource.THOREAU, result?.source)
        assertNull(result?.problem)
    }
}
