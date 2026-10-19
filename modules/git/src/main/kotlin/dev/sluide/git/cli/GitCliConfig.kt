package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The `user.name` git uses in the repository at [root], trimmed; `null` if it is not set, or if git is not
 * available, fails or takes longer than 3 s.
 */
internal fun GitCli.userName(root: Path): String? {
    if (!available) return null
    return run(listOf("--no-optional-locks", "config", "--get", "user.name"), root, 3.seconds)
        ?.trim()
        ?.ifEmpty { null }
}

/**
 * The names of the remotes of the repository at [root] (`git remote`), in git's order; `null` if git is not
 * available, fails or takes longer than 3 s. Git refuses line breaks in remote names, so one per line is safe.
 */
internal fun GitCli.remoteNames(
    root: Path,
    cancellation: GitCancellation? = null,
): List<String>? {
    if (!available) return null
    return run(
        listOf("--no-optional-locks", "remote"),
        root,
        3.seconds,
        cancellation = cancellation,
    )?.lines()?.filter(String::isNotEmpty)
}

/**
 * The remotes of the repository at [root] that `git fetch --all` leaves out: `remote.<name>.skipFetchAll` true, its
 * last value counting as for git (a repository's `false` after a global `true`); `null` if git is not available,
 * fails (also for a value that is not a boolean) or takes longer than 3 s.
 */
internal fun GitCli.remotesSkippedByFetchAll(root: Path): Set<String>? {
    if (!available) return null
    val arguments =
        listOf("--no-optional-locks", "config", "-z", "--type=bool", "--get-regexp", "^remote\\..*\\.skipfetchall$")
    val execution = execute(arguments, root, 3.seconds, null, write = false) ?: return null
    if (execution.exitValue == 1) return emptySet()
    if (execution.exitValue != 0 || !execution.complete) return null
    // <key>\n<value> NUL in the order git reads them, the last one counting; the variable's name in lower case, the
    // remote's as written.
    return execution.output
        .toString(Charsets.UTF_8)
        .split('\u0000')
        .filter { '\n' in it }
        .associate { it.substringBefore('\n') to it.substringAfter('\n') }
        .filterValues { it == "true" }
        .keys
        .map { it.removePrefix("remote.").removeSuffix(".skipfetchall") }
        .toSet()
}

/**
 * All values of the configuration variable [key] in the repository at [root], in the order git reads them (`git
 * config --get-all`); empty if it is not set, `null` if git is not available, fails or takes longer than 3 s.
 */
internal fun GitCli.configValues(
    root: Path,
    key: String,
    cancellation: GitCancellation? = null,
): List<String>? {
    if (!available) return null
    val arguments = listOf("--no-optional-locks", "config", "-z", "--get-all", "--end-of-options", key)
    val execution = execute(arguments, root, 3.seconds, null, write = false, cancellation = cancellation) ?: return null
    if (execution.exitValue == 1) return emptyList()
    if (execution.exitValue != 0 || !execution.complete) return null
    return execution.output
        .toString(Charsets.UTF_8)
        .split('\u0000')
        .dropLast(1)
}

/**
 * The boolean configuration variable [key] in the repository at [root] as git reads it (`git config --type=bool
 * --get`: also a value-less entry or any number); `false` if it is not set, `null` if git is not available, fails
 * (also for a value that is not a boolean) or takes longer than 3 s.
 */
internal fun GitCli.configFlag(
    root: Path,
    key: String,
): Boolean? {
    if (!available) return null
    val arguments = listOf("--no-optional-locks", "config", "--type=bool", "--get", "--end-of-options", key)
    val execution = execute(arguments, root, 3.seconds, null, write = false) ?: return null
    if (execution.exitValue == 1) return false
    if (execution.exitValue != 0 || !execution.complete) return null
    return execution.output.toString(Charsets.UTF_8).trim() == "true"
}

/**
 * The configuration git sees in the repository at [root], in the order it reads it, with `include` and `includeIf`
 * files followed and where each entry comes from: `git config --list --includes --show-scope --show-origin -z`,
 * which runs nothing of the repository's (ADR 0053); `null` if git is not available, fails or takes longer than
 * 3 s.
 */
internal fun GitCli.configEntries(root: Path): List<ConfigEntry>? {
    if (!available) return null
    val arguments =
        listOf("--no-optional-locks", "config", "--list", "--includes", "--show-scope", "--show-origin", "-z")
    val execution = execute(arguments, root, 3.seconds, null, write = false, guardFsmonitor = false) ?: return null
    if (execution.exitValue != 0 || !execution.complete) return null
    // Per entry: scope, origin, then key and value separated by a newline (just the key for one without "=").
    val fields =
        execution.output
            .toString(Charsets.UTF_8)
            .split('\u0000')
            .dropLast(1)
    if (fields.size % 3 != 0) return null
    return fields.chunked(3).map { (scope, origin, entry) ->
        ConfigEntry(
            scope,
            origin,
            entry.substringBefore('\n'),
            entry.substringAfter('\n', "").takeIf {
                '\n' in
                    entry
            },
        )
    }
}

/**
 * The top level of the work tree git sees for [root] (`git rev-parse --show-toplevel`, which follows `core.worktree`),
 * with links resolved; `null` if git is not available, fails, has no work tree there, or the path cannot be read.
 */
internal fun GitCli.topLevel(root: Path): Path? {
    if (!available) return null
    val line = run(listOf("--no-optional-locks", "rev-parse", "--show-toplevel"), root, 3.seconds)?.trimEnd('\n')
    if (line.isNullOrEmpty()) return null
    return try {
        root.resolve(line).toRealPath()
    } catch (_: InvalidPathException) {
        null
    } catch (_: IOException) {
        null
    }
}

/**
 * The paths of the submodules in the index of the repository at [root] (gitlinks, mode 160000), relative to it:
 * `git ls-files --stage -z`; `null` if git is not available, fails or takes longer than 10 s.
 */
internal fun GitCli.gitlinks(root: Path): List<String>? {
    if (!available) return null
    val output = run(listOf("--no-optional-locks", "ls-files", "--stage", "-z"), root, 10.seconds) ?: return null
    // Per entry: mode, object, stage, a tab, the path.
    return output
        .split('\u0000')
        .filter { it.startsWith("160000 ") && '\t' in it }
        .map { it.substringAfter('\t') }
        .distinct()
}

/**
 * Whether the user configured how git runs ssh in the repository at [root]: `core.sshCommand` or `ssh.variant`
 * (also given in the environment, `GIT_CONFIG_PARAMETERS`); `null` if git is not available, fails or takes longer
 * than 3 s.
 */
internal fun GitCli.sshConfigured(root: Path): Boolean? {
    if (!available) return null
    val arguments = listOf("--no-optional-locks", "config", "--get-regexp", "^(core\\.sshcommand|ssh\\.variant)$")
    return when (execute(arguments, root, 3.seconds, null, write = false)?.exitValue) {
        0 -> true
        1 -> false
        else -> null
    }
}

/**
 * One entry of git's configuration ([GitCli.configEntries]): its [scope] (`system`, `global`, `local`, `worktree`,
 * `command`), its [origin] as git names it (`file:.git/config`), its [key] (section and variable lower case) and its
 * [value], `null` for an entry without `=`.
 */
internal data class ConfigEntry(
    val scope: String,
    val origin: String,
    val key: String,
    val value: String?,
)
