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

    private class Asked {
        val scripts = mutableListOf<String>()
        val environments = mutableListOf<Map<String, String>>()
    }

    private fun lookup(
        setting: String,
        answer: String?,
        asked: Asked = Asked(),
        environment: Map<String, String> = ide,
    ): DockerLookup =
        DockerExecutable(
            shell = { script, env ->
                asked.scripts += script
                asked.environments += env
                answer
            },
            isExecutable = executables::contains,
        ).resolve(setting, environment)

    @Test
    fun `a blank setting looks docker up, a plain name by command -v, a path or ~ path without it`() {
        val asked = Asked()
        val printed = "/usr/local/bin/docker\n--sluide-environment--\nPATH=/shell/bin\n"

        val found = assertIs<DockerLookup.Found>(lookup("  ", printed, asked)).docker
        assertEquals(Path.of("/usr/local/bin/docker"), found.executable)
        assertEquals(mapOf("HOME" to "/home/me", "PATH" to "/shell/bin"), found.environment)
        lookup("podman", printed, asked)
        lookup(" /opt/podman/bin/podman ", printed, asked)
        lookup("~/bin/docker", printed, asked)
        lookup("bin\\docker", printed, asked)
        lookup("my~docker", printed, asked)
        lookup("~docker", printed, asked)

        assertEquals(
            listOf(
                lookupScript("docker"),
                lookupScript("podman"),
                lookupScript(null),
                lookupScript(null),
                lookupScript(null),
                lookupScript("my~docker"),
                lookupScript(null),
            ),
            asked.scripts,
        )
        assertEquals(ide, asked.environments.first(), "the shell runs with the IDE's environment")
    }

    @Test
    fun `a login shell that does not answer leaves a name to the IDE's PATH and a path as it is`() {
        val onPath = lookup("podman", null, environment = ide + ("PATH" to "/nowhere:/usr/bin"))
        assertEquals(ide + ("PATH" to "/nowhere:/usr/bin"), assertIs<DockerLookup.Found>(onPath).docker.environment)
        assertTrue("did not answer" in assertIs<DockerLookup.NotFound>(lookup("docker", null)).message)
        assertEquals(
            Path.of("/opt/podman/bin/podman"),
            assertIs<DockerLookup.Found>(lookup("/opt/podman/bin/podman", null)).docker.executable,
        )
    }

    @Test
    fun `the default executable check wants a regular file with the execute bit`(
        @TempDir root: Path,
    ) {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"), "no POSIX permissions")
        val executable =
            root.resolve("docker").apply {
                writeText("#!/bin/sh\n")
                setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
            }
        val plain =
            root.resolve("plain").apply {
                writeText("x")
                setPosixFilePermissions(PosixFilePermissions.fromString("rw-r--r--"))
            }
        val folder = root.resolve("folder").createDirectories()
        val resolver = DockerExecutable(shell = { _, _ -> "\n--sluide-environment--\n" })

        assertIs<DockerLookup.Found>(resolver.resolve(executable.toString(), ide))
        assertIs<DockerLookup.NotFound>(resolver.resolve(plain.toString(), ide))
        assertIs<DockerLookup.NotFound>(resolver.resolve(folder.toString(), ide))
        assertIs<DockerLookup.NotFound>(resolver.resolve(root.resolve("missing").toString(), ide))
        val missingName = assertIs<DockerLookup.NotFound>(resolver.resolve("docker", ide))
        assertTrue("not found on the PATH of your login shell" in missingName.message, missingName.message)
    }

    @Test
    fun `connecting gives a CLI for what is found and the reason for what is not`(
        @TempDir root: Path,
    ) {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "no /bin/sh")
        val docker =
            root.resolve("docker").apply {
                writeText("#!/bin/sh\n")
                setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
            }
        val environment = System.getenv() + mapOf("HOME" to root.toString(), "SHELL" to "/bin/sh")

        val ready = assertIs<DockerConnection.Ready>(connectDocker(docker.toString(), environment))
        assertEquals(docker, ready.docker.executable)
        assertIs<ProcessDockerCli>(ready.cli)
        val missing = assertIs<DockerConnection.NotFound>(connectDocker(root.resolve("nope").toString(), environment))
        assertTrue("not an executable file" in missing.message, missing.message)
    }

    @Test
    fun `the lookup ignores what the environment part prints, a name's line before it decides`() {
        // A multi-line variable can print any line; the separator may even come first.
        val onlyEnvironment = parseLookup("--sluide-environment--\nMULTI=a\n/usr/evil/docker\nalias x=y\n", "docker")
        assertEquals(ShellLookup(null, null, emptyMap()), onlyEnvironment)
        assertEquals(
            ShellLookup("/usr/bin/docker", null, emptyMap()),
            parseLookup("alias docker=podman\n/usr/bin/docker\n--sluide-environment--\n", "docker"),
            "a path wins over an alias line",
        )
        assertEquals(
            ShellLookup(null, "docker", emptyMap()),
            parseLookup("\n  \n docker \n--sluide-environment--\n", "docker"),
        )
        assertEquals("ok", parseLookup("x\n/y\n--sluide-environment--\nPATH=ok\n", "docker").environment?.get("PATH"))
    }

    @ParameterizedTest
    @CsvSource(
        "'bin\\docker'",
        "'~docker'",
        "'~other/docker'",
        "'bin/docker'",
        "'a\u0000/b'",
    )
    fun `a setting that is neither a name nor an absolute path is refused with what to enter`(setting: String) {
        val message = assertIs<DockerLookup.NotFound>(resolve(setting, null)).message

        assertTrue("neither a program name nor a full path" in message, message)
    }

    @Test
    fun `a ~ path needs the HOME of the IDE, and ~ alone is the home folder`() {
        val noHome = resolution("~/bin/docker", null, mapOf("PATH" to "/bin")) { true }
        assertTrue("neither a program name" in assertIs<DockerLookup.NotFound>(noHome).message)
        val home = resolution("~", null, ide) { false }
        assertTrue("/home/me is not an executable" in assertIs<DockerLookup.NotFound>(home).message)
        val homeFound = resolution("~", null, ide) { it == Path.of("/home/me") }
        assertEquals(Path.of("/home/me"), assertIs<DockerLookup.Found>(homeFound).docker.executable)
    }

    @Test
    fun `a name the login shell found at a path that is not executable says so`() {
        val message =
            assertIs<DockerLookup.NotFound>(resolve("docker", ShellLookup("/usr/bin/docker", null, emptyMap()))).message

        assertTrue("/usr/bin/docker is not an executable file" in message, message)
    }

    @Test
    fun `without the shell the IDE's PATH is searched in order, absolute folders only`() {
        fun found(
            path: String?,
            executable: (Path) -> Boolean,
        ) = resolution("docker", null, listOfNotNull(path?.let { "PATH" to it }).toMap(), executable)

        val second =
            found("/first:/second") { it == Path.of("/second/docker") || it == Path.of("/first/docker") }
        assertEquals(Path.of("/first/docker"), assertIs<DockerLookup.Found>(second).docker.executable)
        assertEquals(
            Path.of("/second/docker"),
            assertIs<DockerLookup.Found>(
                found("/first: :/second") { it == Path.of("/second/docker") },
            ).docker.executable,
        )
        assertIs<DockerLookup.NotFound>(found("bin") { true }, "relative folders are not searched")
        assertIs<DockerLookup.NotFound>(found(null) { true })
        assertEquals(
            Path.of("/usr/bin/docker"),
            assertIs<DockerLookup.Found>(found("\u0000:/usr/bin") { true }).docker.executable,
        )
    }

    @Test
    fun `the flavor follows the file name, none for a root`() {
        assertEquals(EngineFlavor.PODMAN, flavorOf(Path.of("/usr/bin/podman")))
        assertEquals(EngineFlavor.PODMAN, flavorOf(Path.of("/usr/bin/podman-remote")))
        assertEquals(EngineFlavor.DOCKER, flavorOf(Path.of("/usr/bin/mypodman")))
        assertEquals(EngineFlavor.DOCKER, flavorOf(Path.of("/usr/bin/docker")))
        assertEquals(EngineFlavor.DOCKER, flavorOf(Path.of("/")))
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
