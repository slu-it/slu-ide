package dev.sluide.docker.compose

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposeFilesTest {
    @TempDir
    lateinit var dir: Path

    @ParameterizedTest
    @CsvSource(
        "compose.yaml,true",
        "compose.yml,true",
        "docker-compose.yaml,true",
        "docker-compose.yml,true",
        "compose.prod.yaml,true",
        "docker-compose.dev.yml,true",
        "compose.override.yaml,true",
        "docker-compose.override.yml,true",
        "compose.json,false",
        "compose.a.b.yaml,false",
        "my-compose.yaml,false",
        "application.yaml,false",
        "compose.yaml.bak,false",
    )
    fun `compose file names`(
        name: String,
        expected: Boolean,
    ) = assertEquals(expected, isComposeFileName(name))

    @Test
    fun `the default names are exact and ordered by compose's preference`() {
        assertEquals(
            listOf("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml"),
            COMPOSE_FILE_NAMES.toList(),
        )
    }

    @ParameterizedTest
    @CsvSource(
        "compose.yaml,compose.override.yaml,compose.override.yaml",
        "compose.yml,compose.override.yml,compose.override.yml",
        "compose.yaml,compose.override.yml,compose.override.yml",
        "docker-compose.yml,docker-compose.override.yaml,docker-compose.override.yaml",
        "docker-compose.yaml,docker-compose.override.yml,docker-compose.override.yml",
    )
    fun `a default name picks up its sibling override`(
        base: String,
        override: String,
        expected: String,
    ) {
        dir.resolve(base).createFile()
        dir.resolve(override).createFile()

        assertEquals(dir.resolve(expected), overrideFor(dir.resolve(base)))
    }

    @Test
    fun `the override must match the base, exist and the base must have a default name`() {
        dir.resolve("compose.yaml").createFile()
        dir.resolve("docker-compose.override.yaml").createFile()
        dir.resolve("compose.prod.yaml").createFile()

        assertNull(overrideFor(dir.resolve("compose.yaml")), "docker-compose.override does not pair with compose")
        dir.resolve("compose.override.yaml").createFile()
        assertNull(overrideFor(dir.resolve("compose.prod.yaml")), "a variant gets no automatic override")
        assertNull(overrideFor(dir.resolve("compose.override.yaml")), "an override has no override")
    }

    @Test
    fun `yaml wins over yml when both overrides exist`() {
        dir.resolve("compose.yaml").createFile()
        dir.resolve("compose.override.yml").createFile()
        dir.resolve("compose.override.yaml").createFile()

        assertEquals(dir.resolve("compose.override.yaml"), overrideFor(dir.resolve("compose.yaml")))
    }

    @Test
    fun `the default env file is the dot env beside the file, if there is one`() {
        val file = dir.resolve("compose.yaml").createFile()
        assertNull(defaultEnvFile(file))

        dir.resolve(".env").createFile()

        assertEquals(dir.resolve(".env"), defaultEnvFile(file))
    }
}
