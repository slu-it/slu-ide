package dev.sluide.docker.dockerfile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [BuildCommands]: the arguments, the command line, the environment and the checks of a build. */
class BuildCommandsTest {
    private val file = Path.of("/work/my app/Dockerfile")
    private val folder = Path.of("/work/my app")
    private val info = DockerfileInfo(listOf("deps", "app"), listOf(BuildArg("VERSION", "1"), BuildArg("TOKEN", null)))

    /** The arguments of a build of [file] with [options]; every folder exists, the checks read no disk. */
    private fun args(
        options: BuildOptions,
        info: DockerfileInfo = this.info,
    ): List<String> = BuildCommands.buildArgs(options, file, info, isDirectory = { true })

    @Test
    fun `a plain build names the file and the file's folder as context, both absolute`() {
        assertEquals(listOf("build", "-f", "/work/my app/Dockerfile", "/work/my app"), args(BuildOptions()))
    }

    @Test
    fun `tags, target, passed build args, flags and platform follow in that order, the context last`() {
        val options =
            BuildOptions(
                tags = "myapp:1.0, registry.example.com:5000/team/myapp  latest-local",
                target = "deps",
                buildArgs = mapOf("TOKEN" to "s3cret", "VERSION" to "2"),
                noCache = true,
                pull = true,
                platform = "linux/arm64",
                context = "..",
            )

        assertEquals(
            listOf(
                "build",
                "-f",
                "/work/my app/Dockerfile",
                "-t",
                "myapp:1.0",
                "-t",
                "registry.example.com:5000/team/myapp",
                "-t",
                "latest-local",
                "--target",
                "deps",
                "--build-arg",
                "VERSION=\$SLUIDE_BUILD_ARG_0",
                "--build-arg",
                "TOKEN=\$SLUIDE_BUILD_ARG_1",
                "--no-cache",
                "--pull",
                "--platform",
                "linux/arm64",
                "/work",
            ),
            args(options),
        )
    }

    @Test
    fun `build arg values go into the environment, not the command line, only of the file's ARGs with a value`() {
        val options =
            BuildOptions(buildArgs = mapOf("TOKEN" to "it's secret", "VERSION" to "", "GONE" to "kept in the store"))

        val line = BuildCommands.commandLine("/usr/bin/docker", args(options))

        assertEquals(
            "'/usr/bin/docker' 'build' '-f' '/work/my app/Dockerfile' " +
                "'--build-arg' \"TOKEN=\$SLUIDE_BUILD_ARG_0\" '/work/my app'",
            line,
        )
        assertFalse("secret" in line)
        assertEquals(mapOf("SLUIDE_BUILD_ARG_0" to "it's secret"), BuildCommands.environment(options, info))
    }

    @Test
    fun `build arg values are numbered in the order of the passed args, under private names`() {
        val options = BuildOptions(buildArgs = mapOf("TOKEN" to "t", "VERSION" to "2"))

        assertEquals(
            mapOf("SLUIDE_BUILD_ARG_0" to "2", "SLUIDE_BUILD_ARG_1" to "t"),
            BuildCommands.environment(options, info),
        )
    }

    @Test
    fun `only build arg words of the private names go into double quotes`() {
        assertEquals(
            "'docker' \"A_1=\$SLUIDE_BUILD_ARG_12\" 'A=\$HOME' 'A=\$SLUIDE_BUILD_ARG_x' '-A=\$SLUIDE_BUILD_ARG_0'",
            BuildCommands.commandLine(
                "docker",
                listOf("A_1=\$SLUIDE_BUILD_ARG_12", "A=\$HOME", "A=\$SLUIDE_BUILD_ARG_x", "-A=\$SLUIDE_BUILD_ARG_0"),
            ),
        )
    }

    @Test
    fun `the command line quotes every word for the shell`() {
        assertEquals(
            "'docker' 'build' '-t' 'a'\\''b'",
            BuildCommands.commandLine("docker", listOf("build", "-t", "a'b")),
        )
    }

    @Test
    fun `a stored target that is no longer in the file is passed anyway`() {
        assertEquals(listOf("--target", "old"), args(BuildOptions(target = "old")).drop(3).dropLast(1))
    }

    @Test
    fun `the context is resolved against the file's folder unless absolute`() {
        assertEquals(folder, BuildCommands.contextOf(BuildOptions(context = "  "), file))
        assertEquals(folder.resolve("app"), BuildCommands.contextOf(BuildOptions(context = "./app/"), file))
        assertEquals(Path.of("/srv/ctx"), BuildCommands.contextOf(BuildOptions(context = "/srv/ctx"), file))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "myapp", "myapp:1.0", "my-app_x.y:V1_2.3-rc", "team/my__app", "localhost/app", "localhost:5000/app:dev",
            "registry.example.com/team/app", "Registry.Example.com:443/app:latest", "host:5000/app",
        ],
    )
    fun `valid tags`(tag: String) {
        assertTrue(BuildCommands.isValidTag(tag), tag)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "MyApp", "team/MyApp", "User/app", "-t", "app:", "app:-x", "app:.x", "app::1", "/app", "app/", "a..b",
            "app@sha256:abc", "app:1.0#",
        ],
    )
    fun `invalid tags`(tag: String) {
        assertFalse(BuildCommands.isValidTag(tag), tag)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "tags=MyApp|Not a valid tag: \"MyApp\" (lower-case name[:tag], e.g. myapp:1.0)",
            "target=-x|Not a valid target: \"-x\"",
            "platform=linux|Not a valid platform: \"linux\" (os/arch[/variant], e.g. linux/amd64)",
            "platform=a/b/c/d|Not a valid platform: \"a/b/c/d\" (os/arch[/variant], e.g. linux/amd64)",
            "arg=1BAD|Not a valid build arg name: \"1BAD\"",
            "nul=VERSION|The value of VERSION contains a NUL character",
        ],
    )
    fun `invalid options give a message and no command`(
        setting: String,
        message: String,
    ) {
        val (key, value) = setting.split('=', limit = 2)
        val options =
            when (key) {
                "tags" -> BuildOptions(tags = value)
                "target" -> BuildOptions(target = value)
                "platform" -> BuildOptions(platform = value)
                "arg" -> BuildOptions(buildArgs = mapOf(value to "x"))
                else -> BuildOptions(buildArgs = mapOf(value to "a\u0000b"))
            }
        val info = info.copy(args = info.args + BuildArg("1BAD", null))

        assertEquals(message, BuildCommands.problem(options, info, folder) { true })
        val failure =
            assertFailsWith<IllegalArgumentException> {
                BuildCommands.buildArgs(options, file, info, isDirectory = { true })
            }
        assertEquals(message, failure.message)
    }

    @Test
    fun `a missing context folder is a problem`() {
        assertEquals(
            "The context folder does not exist: /nowhere",
            BuildCommands.problem(BuildOptions(), info, Path.of("/nowhere")) { false },
        )
    }

    @Test
    fun `valid platforms and an empty target and platform are no problem`() {
        for (platform in listOf("", "linux/amd64", "linux/arm64", "linux/arm/v7")) {
            assertNull(BuildCommands.problem(BuildOptions(platform = platform), info, folder) { true }, platform)
        }
    }
}
