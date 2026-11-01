package dev.sluide.docker.compose

import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** The names compose looks for by itself, in its order of preference. */
val COMPOSE_FILE_NAMES: Set<String> =
    linkedSetOf("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml")

/**
 * Patterns of names of variants that are compose files too: `compose.prod.yaml`, `docker-compose.dev.yml` and, as
 * `override` is one such middle part, `compose.override.yaml`.
 */
val COMPOSE_FILE_PATTERNS: List<Regex> = listOf(Regex("(docker-)?compose\\.[^.]+\\.ya?ml"))

/** Whether a file called [name] is a compose file: one of [COMPOSE_FILE_NAMES] or matching [COMPOSE_FILE_PATTERNS]. */
fun isComposeFileName(name: String): Boolean =
    name in COMPOSE_FILE_NAMES || COMPOSE_FILE_PATTERNS.any { it.matches(name) }

/**
 * The override file compose would add by itself for [file], or `null`. Thoreau always passes `-f`, which switches
 * compose's own pick-up of the override off, so the view adds it explicitly: only when [file] has a default name
 * (`compose.yaml` or `docker-compose.yml`, see [COMPOSE_FILE_NAMES]) and a sibling `<same prefix>.override.yaml` or
 * `.override.yml` exists (`compose.yaml` pairs with `compose.override.*`, `docker-compose.*` with
 * `docker-compose.override.*`; `.yaml` before `.yml`). Checks the disk.
 */
fun overrideFor(file: Path): Path? {
    val name = file.name
    if (name !in COMPOSE_FILE_NAMES) return null
    val prefix = if (name.startsWith("docker-")) "docker-compose" else "compose"
    return listOf("yaml", "yml")
        .map { file.resolveSibling("$prefix.override.$it") }
        .firstOrNull { it.isRegularFile() }
}

/** The `.env` beside [file], if it exists: the file compose reads by itself when no `--env-file` is given. */
fun defaultEnvFile(file: Path): Path? = file.resolveSibling(".env").takeIf { it.isRegularFile() }
