package dev.sluide.git.cli

import java.io.IOException
import java.util.concurrent.TimeUnit

private val log = System.getLogger(GitCli::class.java.name)

/**
 * The variables git lists with `--local-env-vars` but keeps when it switches repositories (submodules): configuration
 * given in the environment (`git -c` of an outer git, `GIT_CONFIG_COUNT` with its keys and values, e.g.
 * `safe.directory` in a container), which is the user's and points at no repository.
 */
internal val CONFIGURATION_VARIABLES = setOf("GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT")

/**
 * The variables that point git at a repository as git 2.53 lists them (`git rev-parse --local-env-vars`), without the
 * [CONFIGURATION_VARIABLES].
 */
internal val KNOWN_REPOSITORY_VARIABLES =
    listOf(
        "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        "GIT_CONFIG",
        "GIT_OBJECT_DIRECTORY",
        "GIT_DIR",
        "GIT_WORK_TREE",
        "GIT_IMPLICIT_WORK_TREE",
        "GIT_GRAFT_FILE",
        "GIT_INDEX_FILE",
        "GIT_NO_REPLACE_OBJECTS",
        "GIT_REPLACE_REF_BASE",
        "GIT_PREFIX",
        "GIT_SHALLOW_FILE",
        "GIT_COMMON_DIR",
    )

/**
 * The variables of [environment] that point git at a repository (ADR 0052): those `git rev-parse --local-env-vars`
 * lists, run with [executable] (so a newer git's are known too), and the [KNOWN_REPOSITORY_VARIABLES]; as for git, not
 * the [CONFIGURATION_VARIABLES]. Git is asked only if one of the known ones is set, and is given none of them; if it
 * cannot be asked within 3 s, the known ones count.
 */
internal fun repositoryVariables(
    executable: String,
    environment: Map<String, String>,
): Set<String> {
    val known = KNOWN_REPOSITORY_VARIABLES.filter { it in environment }
    if (known.isEmpty()) return emptySet()
    val listed =
        try {
            val process =
                ProcessBuilder(executable, "rev-parse", "--local-env-vars")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .apply {
                        environment().clear()
                        environment().putAll(environment - known.toSet())
                    }.start()
            process.outputStream.close()
            // A few short lines: they fit in the pipe, so they can be read once git has ended.
            if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) {
                process.inputStream
                    .use { it.readAllBytes() }
                    .toString(
                        Charsets.UTF_8,
                    ).lines()
                    .filter(String::isNotEmpty)
            } else {
                process.destroyForcibly()
                emptyList()
            }
        } catch (e: IOException) {
            log.log(System.Logger.Level.DEBUG, "Cannot run $executable", e)
            emptyList()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyList()
        }
    val variables = (known + listed.filter { it in environment && it !in CONFIGURATION_VARIABLES }).toSet()
    log.log(System.Logger.Level.INFO, "git runs without ${variables.sorted().joinToString(", ")} of the environment")
    return variables
}
