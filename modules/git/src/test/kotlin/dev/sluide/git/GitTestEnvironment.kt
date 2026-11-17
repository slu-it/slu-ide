package dev.sluide.git

import dev.sluide.git.cli.GitCli

/**
 * The start of a script that stands in for git: it answers `--version`, and fails what `GitCli` asks on its own (the
 * variables of `git rev-parse --local-env-vars`, a `core.fsmonitor` hook, ADR 0052) as a git without them would, so
 * that what follows sees only the commands under test.
 */
internal const val FAKE_GIT_START =
    "#!/bin/sh\n[ \"\$1\" = --version ] && exit 0\ncase \" \$* \" in *' --local-env-vars '*|*' core.fsmonitor '*) exit 1;; esac\n"

/**
 * The environment for git in tests: no global or system configuration, so the user's settings do not interfere, and
 * none of the inherited `GIT_*` variables. A build run from a git hook or `git rebase -x` inherits `GIT_DIR`, which
 * would point the tests' git commands at the slu-ide repository itself.
 */
internal fun testGitEnvironment(): Map<String, String> =
    System.getenv().filterKeys { !it.startsWith("GIT_") } +
        mapOf(
            "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "XDG_CONFIG_HOME" to emptyConfigHome.toString(),
        )

/**
 * An empty folder for `XDG_CONFIG_HOME`: git reads the global excludes (`core.excludesFile`, by default
 * `$XDG_CONFIG_HOME/git/ignore`) even without a global configuration, and a Java developer's list often has `*.class`.
 */
private val emptyConfigHome: java.nio.file.Path by lazy {
    java.nio.file.Files.createTempDirectory("sluide-git-config").also { folder ->
        Runtime.getRuntime().addShutdownHook(Thread { folder.toFile().deleteRecursively() })
    }
}

/**
 * A git that fails every call with one of the [words] among its arguments, from the call after the first [after] of
 * them, and runs the real git for the rest, to reach the code that handles one failing command.
 */
internal fun failingGit(
    folder: java.nio.file.Path,
    vararg words: String,
    after: Int = 0,
): GitCli {
    val script = folder.resolve("failing-git")
    val counter = folder.resolve("failing-git-count")
    val patterns = words.joinToString("|") { "*' $it '*" }
    val bump = "n=\$(cat '$counter' 2>/dev/null || echo 0); n=\$((n+1)); echo \$n > '$counter';"
    val count = "$bump [ \$n -gt $after ] && exit 1;;"
    script.toFile().writeText(FAKE_GIT_START + "case \" \$* \" in $patterns) $count esac\nexec git \"\$@\"\n")
    script.toFile().setExecutable(true)
    return GitCli(script.toString(), testGitEnvironment())
}
