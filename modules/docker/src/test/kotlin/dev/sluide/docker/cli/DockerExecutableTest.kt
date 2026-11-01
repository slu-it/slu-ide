package dev.sluide.docker.cli

import dev.sluide.docker.model.EngineFlavor
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DockerExecutableTest {
    private val ide = mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/home/me", "DOCKER_HOST" to "unix:///ide.sock")
    private val executables =
        setOf(Path.of("/usr/local/bin/docker"), Path.of("/opt/podman/bin/podman"), Path.of("/usr/bin/podman"))

    private fun resolve(
        value: String,
        lookup: ShellLookup?,
    ) = resolution(value, lookup, ide, executables::contains)

    @Test
    fun `the lookup's output gives the path and the captured variables, nothing else`() {
        val output =
            "/usr/local/bin/docker\n--sluide-environment--\nPATH=/opt/homebrew/bin:/usr/bin\nHOME=/home/me\n" +
                "DOCKER_HOST=unix:///Users/me/.colima/default/docker.sock\nDOCKER_CONTEXT=\nPATH=/second\n" +
                "XDG_RUNTIME_DIR=/run/user/501\n"

        assertEquals(
            ShellLookup(
                "/usr/local/bin/docker",
                null,
                mapOf(
                    "PATH" to "/opt/homebrew/bin:/usr/bin",
                    "DOCKER_HOST" to "unix:///Users/me/.colima/default/docker.sock",
                    "DOCKER_CONTEXT" to "",
                    "XDG_RUNTIME_DIR" to "/run/user/501",
                ),
            ),
            parseLookup(output, "docker"),
        )
        assertEquals(ShellLookup(null, null, emptyMap()), parseLookup("--sluide-environment--\n", "docker"))
        assertEquals(ShellLookup(null, null, null), parseLookup("", "docker"), "no environment printed")
    }

    @ParameterizedTest
    @CsvSource(
        "'docker: Command not found.\\n/usr/local/bin/docker', /usr/local/bin/docker, ''",
        "'command: Command not found.', '', ''",
        "'alias docker=podman', '', alias docker=podman",
        "'alias docker=''podman''', '', 'alias docker=''podman'''",
        "'docker: aliased to podman', '', docker: aliased to podman",
        "'docker', '', docker",
        "'some error\\ndocker', '', docker",
    )
    fun `command -v output is a path, an alias or function, or nothing found`(
        printed: String,
        path: String,
        alias: String,
    ) {
        val lookup = parseLookup(printed.replace("\\n", "\n") + "\n--sluide-environment--\n", "docker")

        assertEquals(path.ifEmpty { null } to alias.ifEmpty { null }, lookup.path to lookup.alias)
    }

    @Test
    fun `a name found by the login shell runs with the shell's captured variables instead of the IDE's`() {
        val found =
            resolve(
                "docker",
                ShellLookup(
                    "/usr/local/bin/docker",
                    null,
                    mapOf("PATH" to "/opt/homebrew/bin", "DOCKER_HOST" to "", "DOCKER_CONTEXT" to "colima"),
                ),
            )

        val docker = assertIs<DockerLookup.Found>(found).docker
        assertEquals(Path.of("/usr/local/bin/docker"), docker.executable)
        assertEquals(EngineFlavor.DOCKER, docker.flavor)
        // The IDE's DOCKER_HOST is stale: the shell sets it empty, so the CLI does not get it.
        assertEquals(
            mapOf("HOME" to "/home/me", "PATH" to "/opt/homebrew/bin", "DOCKER_CONTEXT" to "colima"),
            docker.environment,
        )
        val podman =
            assertIs<DockerLookup.Found>(
                resolve("podman", ShellLookup("/opt/podman/bin/podman", null, mapOf("PATH" to "/usr/bin"))),
            ).docker
        assertEquals(EngineFlavor.PODMAN, podman.flavor)
        assertEquals(null, podman.environment["DOCKER_HOST"], "a variable the shell does not set is removed")
        val withoutEnvironment = resolve("docker", ShellLookup("/usr/local/bin/docker", null, null))
        assertEquals(
            ide,
            assertIs<DockerLookup.Found>(withoutEnvironment).docker.environment,
            "no environment: the IDE's",
        )
    }

    @Test
    fun `a name the shell does not find, or finds as an alias or function, is not found with what to do`() {
        val missing = assertIs<DockerLookup.NotFound>(resolve("docker", ShellLookup(null, null, emptyMap())))
        assertTrue("not found on the PATH of your login shell" in missing.message, missing.message)
        assertTrue("Settings → Docker" in missing.message)

        val alias =
            assertIs<DockerLookup.NotFound>(resolve("docker", ShellLookup(null, "alias docker=podman", emptyMap())))
        assertTrue("alias or a function (alias docker=podman)" in alias.message, alias.message)
    }

    @Test
    fun `a full path is used as it is when executable, with the shell's variables`() {
        val found =
            resolve(
                "/usr/bin/podman",
                ShellLookup(null, null, mapOf("CONTAINER_HOST" to "ssh://core@localhost:22/run")),
            )

        val docker = assertIs<DockerLookup.Found>(found).docker
        assertEquals(Path.of("/usr/bin/podman"), docker.executable)
        assertEquals("ssh://core@localhost:22/run", docker.environment["CONTAINER_HOST"])
        assertIs<DockerLookup.Found>(resolution("~/bin/docker", null, ide) { it == Path.of("/home/me/bin/docker") })
        assertTrue("not an executable file" in assertIs<DockerLookup.NotFound>(resolve("/nope/docker", null)).message)
        assertTrue(
            "neither a program name nor a full path" in
                assertIs<DockerLookup.NotFound>(resolve("bin/docker", null)).message,
        )
    }

    @Test
    fun `without an answer from the login shell a name is looked up on the IDE's PATH`() {
        val found = resolution("podman", null, ide + ("PATH" to "/nowhere:/usr/bin"), executables::contains)

        assertEquals(Path.of("/usr/bin/podman"), assertIs<DockerLookup.Found>(found).docker.executable)
        assertTrue("did not answer" in assertIs<DockerLookup.NotFound>(resolve("docker", null)).message)
    }

    @Test
    fun `the script looks the quoted name up and prints the environment after a separator`() {
        assertEquals("command -v 'docker'\nprintf '%s\\n' '--sluide-environment--'\nenv", lookupScript("docker"))
        assertEquals("printf '%s\\n' '--sluide-environment--'\nenv", lookupScript(null))
        assertEquals("'it'\\''s'", shellQuoted("it's"))
    }

    @Test
    fun `a stub docker on the PATH of a real login shell is found with that shell's variables`(
        @TempDir root: Path,
    ) {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "no /bin/sh")
        val home = root.resolve("home").createDirectories()
        val bin = root.resolve("bin").createDirectories()
        bin.resolve("docker").apply {
            writeText("#!/bin/sh\n")
            setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        home
            .resolve(
                ".profile",
            ).writeText(
                "PATH=\"$bin:/usr/bin:/bin\"\nexport PATH\nDOCKER_HOST=tcp://shell:2375\nexport DOCKER_HOST\necho noise\n",
            )
        val environment =
            System.getenv() +
                mapOf("HOME" to home.toString(), "SHELL" to "/bin/sh", "DOCKER_HOST" to "unix:///ide.sock")

        val docker = assertIs<DockerLookup.Found>(DockerExecutable().resolve("docker", environment)).docker

        assertEquals(bin.resolve("docker"), docker.executable)
        assertEquals("tcp://shell:2375", docker.environment["DOCKER_HOST"])
        assertEquals("$bin:/usr/bin:/bin", docker.environment["PATH"])
    }
}
