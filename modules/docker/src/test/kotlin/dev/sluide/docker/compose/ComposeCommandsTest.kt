package dev.sluide.docker.compose

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComposeCommandsTest {
    private val file = Path.of("/work/my app/compose.yaml")

    private fun base(
        options: ComposeOptions = ComposeOptions(),
        override: Path? = null,
        allProfiles: Boolean = false,
    ) = ComposeCommands.baseArgs(file, override, options, allProfiles)

    @Test
    fun `the base is compose with the file as an absolute path`() {
        assertEquals(listOf("compose", "-f", "/work/my app/compose.yaml"), base())
    }

    @Test
    fun `a relative file is made absolute so that it cannot pose as a flag`() {
        val args = ComposeCommands.baseArgs(Path.of("-evil.yaml"), null, ComposeOptions())

        assertEquals(Path.of("-evil.yaml").toAbsolutePath().toString(), args[2])
    }

    @Test
    fun `the override, project name, env files and sorted profiles follow in that order`() {
        val options =
            ComposeOptions(
                profiles = setOf("tools", "debug"),
                projectName = "demo",
                envFiles = listOf(".env", "env/prod.env", "../shared.env"),
            )

        assertEquals(
            listOf(
                "compose",
                "-f",
                "/work/my app/compose.yaml",
                "-f",
                "/work/my app/compose.override.yaml",
                "-p",
                "demo",
                "--env-file",
                "/work/my app/.env",
                "--env-file",
                "/work/my app/env/prod.env",
                "--env-file",
                "/work/shared.env",
                "--profile",
                "debug",
                "--profile",
                "tools",
            ),
            base(options, Path.of("/work/my app/compose.override.yaml")),
        )
    }

    @Test
    fun `no env files given passes none, as compose reads the dot env itself, an empty list reads none at all`() {
        assertEquals(listOf("compose", "-f", "/work/my app/compose.yaml"), base(ComposeOptions(envFiles = null)))
        assertEquals(
            listOf("compose", "-f", "/work/my app/compose.yaml", "--env-file", "/dev/null"),
            base(ComposeOptions(envFiles = emptyList())),
        )
    }

    @Test
    fun `an env file starting with a dash becomes an absolute path`() {
        val args = base(ComposeOptions(envFiles = listOf("--help")))

        assertEquals(listOf("--env-file", "/work/my app/--help"), args.takeLast(2))
    }

    @Test
    fun `all profiles replaces the chosen ones with a star`() {
        val args = base(ComposeOptions(profiles = setOf("debug")), allProfiles = true)

        assertEquals(listOf("--profile", "*"), args.takeLast(2))
        assertEquals(1, args.count { it == "--profile" })
    }

    @Test
    fun `invalid project and profile names are refused`() {
        assertFailsWith<IllegalArgumentException> { base(ComposeOptions(projectName = "--help")) }
        assertFailsWith<IllegalArgumentException> { base(ComposeOptions(projectName = "Demo")) }
        assertFailsWith<IllegalArgumentException> { base(ComposeOptions(profiles = setOf("-x"))) }
    }

    @Test
    fun `up takes its flags from the options`() {
        assertEquals(listOf("up", "-d"), ComposeCommands.up(ComposeOptions()))
        assertEquals(listOf("up"), ComposeCommands.up(ComposeOptions(detached = false)))
        assertEquals(
            listOf("up", "-d", "--build", "--force-recreate", "--remove-orphans", "web", "db"),
            ComposeCommands.up(
                ComposeOptions(build = true, forceRecreate = true, removeOrphans = true),
                listOf("web", "db"),
            ),
        )
    }

    @Test
    fun `up of a service is always detached and keeps the flags`() {
        assertEquals(
            listOf("up", "-d", "--build", "web"),
            ComposeCommands.upService(ComposeOptions(detached = false, build = true), "web"),
        )
    }

    @Test
    fun `the other verbs`() {
        assertEquals(listOf("down"), ComposeCommands.down(false))
        assertEquals(listOf("down", "-v"), ComposeCommands.down(true))
        assertEquals(listOf("build"), ComposeCommands.build())
        assertEquals(listOf("build", "web"), ComposeCommands.build(listOf("web")))
        assertEquals(listOf("pull", "db"), ComposeCommands.pull(listOf("db")))
        assertEquals(listOf("restart"), ComposeCommands.restart())
        assertEquals(listOf("stop", "web"), ComposeCommands.stop(listOf("web")))
        assertEquals(listOf("start", "web"), ComposeCommands.start(listOf("web")))
        assertEquals(listOf("logs", "-f", "web"), ComposeCommands.logs(listOf("web")))
        assertEquals(listOf("exec", "web", "sh", "-c"), ComposeCommands.shell("web").take(4))
    }

    @Test
    fun `a service name that looks like a flag is refused`() {
        assertFailsWith<IllegalArgumentException> { ComposeCommands.stop(listOf("--all")) }
        assertFailsWith<IllegalArgumentException> { ComposeCommands.upService(ComposeOptions(), "-x") }
        assertFailsWith<IllegalArgumentException> { ComposeCommands.shell(" ") }
    }

    @Test
    fun `the command line quotes every word for the login shell`() {
        val line =
            ComposeCommands.commandLine(
                "/usr/local/bin/docker",
                listOf("compose", "-f", "/work/my app/it's.yaml", "up", "-d"),
            )

        assertEquals(
            "'/usr/local/bin/docker' 'compose' '-f' '/work/my app/it'\\''s.yaml' 'up' '-d'",
            line,
        )
    }

    @Test
    fun `the variables go into the environment, not the command line, the last of a name wins`() {
        val options =
            ComposeOptions(
                variables =
                    listOf(
                        ComposeVariable("A", "1"),
                        ComposeVariable("B", "x y"),
                        ComposeVariable("A", "2"),
                        ComposeVariable("bad name", "z"),
                    ),
            )

        assertEquals(mapOf("A" to "2", "B" to "x y"), ComposeCommands.environment(options))
        assertEquals(false, base(options).any { it == "x y" || it == "1" })
    }

    @Test
    fun `a project-wide down uses every profile, whatever was chosen`() {
        val options = ComposeOptions(profiles = setOf("debug"), projectName = "demo")

        val args = ComposeCommands.downArgs(file, null, options, removeVolumes = true)

        assertEquals(
            listOf("compose", "-f", "/work/my app/compose.yaml", "-p", "demo", "--profile", "*", "down", "-v"),
            args,
        )
    }

    @Test
    fun `a blank env file entry is refused`() {
        assertFailsWith<IllegalArgumentException> { base(ComposeOptions(envFiles = listOf(" "))) }
    }

    @Test
    fun `variables with a NUL character are left out of the environment`() {
        val options = ComposeOptions(variables = listOf(ComposeVariable("A", "x\u0000y"), ComposeVariable("B", "ok")))

        assertEquals(mapOf("B" to "ok"), ComposeCommands.environment(options))
    }
}
