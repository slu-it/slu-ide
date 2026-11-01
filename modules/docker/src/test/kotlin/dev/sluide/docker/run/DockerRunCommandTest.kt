package dev.sluide.docker.run

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DockerRunCommandTest {
    private val project = Path.of("/work/shop")
    private val home = Path.of("/home/me")
    private val image = "nginx:1"

    private fun args(options: RunOptions) = runArgs(options, project, home)

    @ParameterizedTest
    @ValueSource(
        strings = [
            "80", "8080:80", "8080:80/udp", "127.0.0.1:8080:80", "127.0.0.1:8080:80/tcp", "[::1]:8080:80", "65535:1",
        ],
    )
    fun `valid ports are accepted`(port: String) {
        assertNull(validate(RunOptions(image, ports = listOf(port))))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "0", "65536", "abc", "80:", ":80", "80/sctp", "1.2.3:80:80", "300.0.0.1:80:80", "1:2:3:4", "80/", "8 0",
            "80\u0000",
        ],
    )
    fun `invalid ports are refused`(port: String) {
        assertNotNull(validate(RunOptions(image, ports = listOf(port))), port)
    }

    @ParameterizedTest
    @ValueSource(strings = ["web", "a", "web-1.x_y", "9lives"])
    fun `valid names are accepted`(name: String) {
        assertNull(validate(RunOptions(image, name = name)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["-web", ".web", "we b", "web/1", "we\u0000b"])
    fun `invalid names are refused`(name: String) {
        assertNotNull(validate(RunOptions(image, name = name)), name)
    }

    @ParameterizedTest
    @ValueSource(strings = ["A", "_x", "MY_VAR2"])
    fun `valid variable names are accepted`(name: String) {
        assertNull(validate(RunOptions(image, environment = listOf(RunEnvVar(name, "v")))))
    }

    @ParameterizedTest
    @ValueSource(strings = ["1A", "A-B", "A B", "A="])
    fun `invalid variable names are refused`(name: String) {
        assertNotNull(validate(RunOptions(image, environment = listOf(RunEnvVar(name, "v")))), name)
    }

    @ParameterizedTest
    @CsvSource("/data,/data", "./data,/app", "~/x,/app", "dbdata,/var/lib/x", "my.vol-1,/x")
    fun `valid volumes are accepted`(
        source: String,
        target: String,
    ) {
        assertNull(validate(RunOptions(image, volumes = listOf(RunVolume(source, target)))))
    }

    @ParameterizedTest
    @CsvSource("/data,app", "/data,./app", "../x,/app", "-v,/app", "a:b,/app", "/data,/a:b", ",/app")
    fun `invalid volumes are refused`(
        source: String?,
        target: String,
    ) {
        assertNotNull(validate(RunOptions(image, volumes = listOf(RunVolume(source.orEmpty(), target)))))
    }

    @Test
    fun `other invalid input is refused with a message`() {
        assertNotNull(validate(RunOptions("-x")))
        assertNotNull(validate(RunOptions(image, command = "echo 'open")))
        assertNotNull(validate(RunOptions(image, command = "a\u0000b")))
        assertNotNull(validate(RunOptions(image, environment = listOf(RunEnvVar("A", "x\u0000y")))))
        assertNotNull(validate(RunOptions(image, environment = listOf(RunEnvVar("A", "1"), RunEnvVar("A", "2")))))
        assertNotNull(validate(RunOptions(image, network = "-host")))
    }

    @Test
    fun `blank rows are ignored`() {
        val options =
            RunOptions(
                image,
                ports = listOf(" "),
                environment = listOf(RunEnvVar("", "")),
                volumes = listOf(RunVolume("", "")),
            )

        assertNull(validate(options))
        assertEquals(listOf("run", "-d", image), args(options))
    }

    @Test
    fun `arguments follow the documented order`() {
        val options =
            RunOptions(
                image,
                name = "web",
                ports = listOf("8080:80", "443"),
                environment = listOf(RunEnvVar("A", "1"), RunEnvVar("B", "two words")),
                volumes = listOf(RunVolume("/data", "/d", readOnly = true), RunVolume("dbdata", "/db")),
                network = "shop",
                command = "sh -c 'echo hi'",
                detached = true,
                removeWhenStopped = true,
            )

        assertEquals(
            listOf(
                "run",
                "-d",
                "--rm",
                "--name",
                "web",
                "-p",
                "8080:80",
                "-p",
                "443",
                "-e",
                "A=1",
                "-e",
                "B=two words",
                "-v",
                "/data:/d:ro",
                "-v",
                "dbdata:/db",
                "--network",
                "shop",
                image,
                "sh",
                "-c",
                "echo hi",
            ),
            args(options),
        )
    }

    @Test
    fun `attached runs have no -d`() {
        assertEquals(listOf("run", image), args(RunOptions(image, detached = false)))
    }

    @Test
    fun `detached arguments carry the values inline, names are trimmed`() {
        val options = RunOptions(image, environment = listOf(RunEnvVar(" A ", "s3cret"), RunEnvVar("B", "")))

        assertEquals(listOf("run", "-d", "-e", "A=s3cret", "-e", "B=", image), args(options))
    }

    @Test
    fun `an attached run passes values through private terminal variables the shell expands in double quotes`() {
        val options =
            RunOptions(
                image,
                environment = listOf(RunEnvVar("PATH", "/x y"), RunEnvVar("JAVA_HOME", "it's")),
                detached = false,
            )

        assertEquals(
            "'docker' 'run' '-e' \"PATH=\$SLUIDE_RUN_ENV_0\" '-e' \"JAVA_HOME=\$SLUIDE_RUN_ENV_1\" 'nginx:1'",
            attachedCommand("docker", options, project, home),
        )
        assertEquals(
            mapOf("SLUIDE_RUN_ENV_0" to "/x y", "SLUIDE_RUN_ENV_1" to "it's"),
            attachedEnvironment(options),
        )
    }

    @Test
    fun `attached variables are numbered over the rows that count`() {
        val options =
            RunOptions(image, environment = listOf(RunEnvVar("", ""), RunEnvVar("A", "1")), detached = false)

        assertEquals(mapOf("SLUIDE_RUN_ENV_0" to "1"), attachedEnvironment(options))
        assertTrue("\"A=\$SLUIDE_RUN_ENV_0\"" in attachedCommand("docker", options, project, home))
    }

    @Test
    fun `relative and home sources resolve, other sources stay`() {
        val volumes =
            listOf(
                RunVolume("./data/../logs", "/a"),
                RunVolume("~/cache", "/b"),
                RunVolume("/abs", "/c"),
                RunVolume("named", "/d"),
            )

        assertEquals(
            listOf("-v", "/work/shop/logs:/a", "-v", "/home/me/cache:/b", "-v", "/abs:/c", "-v", "named:/d"),
            args(RunOptions(image, volumes = volumes)).drop(2).dropLast(1),
        )
        assertTrue(
            "/home/me/x:/a" in
                runArgs(RunOptions(image, volumes = listOf(RunVolume("./x", "/a"))), null, home)
                    .joinToString(" "),
            "without a project ./ resolves against home",
        )
    }

    @Test
    fun `the preview masks the values and the copy text includes them quoted`() {
        val options =
            RunOptions(
                image,
                environment = listOf(RunEnvVar("MSG", "it's here"), RunEnvVar("PLAIN", "x")),
                command = "echo 'a b'",
            )

        assertEquals(
            "docker run -d -e 'MSG=••••' -e 'PLAIN=••••' nginx:1 echo 'a b'",
            previewText("docker", options, project, home),
        )
        assertEquals(
            "'docker' 'run' '-d' '-e' 'MSG=it'\\''s here' '-e' 'PLAIN=x' 'nginx:1' 'echo' 'a b'",
            copyText("docker", options, project, home),
        )
    }

    @Test
    fun `command words that look like options are kept as they are`() {
        val options = RunOptions(image, command = "sh -e script.sh", environment = listOf(RunEnvVar("A", "1")))

        assertEquals(
            "'docker' 'run' '-d' '-e' 'A=1' 'nginx:1' 'sh' '-e' 'script.sh'",
            copyText("docker", options, project, home),
        )
        assertEquals("docker run -d -e 'A=••••' nginx:1 sh -e script.sh", previewText("docker", options, project, home))
    }

    @ParameterizedTest
    @CsvSource(
        "nginx:1,nginx",
        "nginx,nginx",
        "reg:5000/app:1.0,reg:5000/app",
        "reg:5000/app,reg:5000/app",
        "app@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef,app",
        "ghcr.io/o/app:v2,ghcr.io/o/app",
    )
    fun `repositoryOf drops tag and digest`(
        reference: String,
        repository: String,
    ) {
        assertEquals(repository, repositoryOf(reference))
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["0123456789ab", "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"],
    )
    fun `an image id has no repository`(id: String) {
        assertNull(repositoryOf(id))
    }
}
