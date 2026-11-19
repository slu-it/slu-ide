package dev.sluide.git

import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
 * them, and runs the real git for the rest, to reach the code that handles one failing command. It fails as git does
 * (checked with git 2.56): exit 128 and a `fatal:` line on standard error; exit 1 would mean "not set" to `git config`
 * and "no merge base" to `git merge-base`.
 */
internal fun failingGit(
    folder: java.nio.file.Path,
    vararg words: String,
    after: Int = 0,
): GitCli {
    val id = System.nanoTime()
    val script = folder.resolve("failing-git-$id")
    val counter = folder.resolve("failing-git-$id.count")
    val patterns = words.joinToString("|") { "*' $it '*" }
    val bump = "n=\$(cat '$counter' 2>/dev/null || echo 0); n=\$((n+1)); echo \$n > '$counter';"
    val count = "$bump [ \$n -gt $after ] && { echo 'fatal: refused by test' >&2; exit 128; };;"
    script.toFile().writeText(FAKE_GIT_START + "case \" \$* \" in $patterns) $count esac\nexec git \"\$@\"\n")
    script.toFile().setExecutable(true)
    return GitCli(script.toString(), testGitEnvironment())
}

/**
 * A git that fails every call with one of the [words] among its arguments, but only after a call with [marker] among
 * its arguments has run (as git does: exit 128 and a `fatal:` line), and runs the real git for the rest: to fail the
 * reads that follow the command under test, whatever number of reads run before it.
 */
internal fun failingAfterGit(
    folder: java.nio.file.Path,
    marker: String,
    vararg words: String,
): GitCli {
    val id = System.nanoTime()
    val script = folder.resolve("failing-after-git-$id")
    val flag = folder.resolve("failing-after-git-$id.ran")
    val patterns = words.joinToString("|") { "*' $it '*" }
    script.toFile().writeText(
        FAKE_GIT_START + "case \" \$* \" in *' $marker '*) touch '$flag';; esac\n" +
            "case \" \$* \" in $patterns) [ -e '$flag' ] && { echo 'fatal: refused by test' >&2; exit 128; };; esac\n" +
            "exec git \"\$@\"\n",
    )
    script.toFile().setExecutable(true)
    return GitCli(script.toString(), testGitEnvironment())
}

/** [failingGit] for only the [nth] (1-based) call with [word] among its arguments, which fails as git does (exit 128). */
internal fun failingNthGit(
    folder: java.nio.file.Path,
    word: String,
    nth: Int,
): GitCli {
    val id = System.nanoTime()
    val counter = folder.resolve("nth-git-$id.count")
    val script = folder.resolve("nth-git-$id")
    val bump = "n=\$(cat '$counter' 2>/dev/null || echo 0); n=\$((n+1)); echo \$n > '$counter';"
    script.toFile().writeText(
        FAKE_GIT_START + "case \" \$* \" in *' $word '*) $bump " +
            "[ \$n -eq $nth ] && { echo 'fatal: refused by test' >&2; exit 128; };; esac\nexec git \"\$@\"\n",
    )
    script.toFile().setExecutable(true)
    return GitCli(script.toString(), testGitEnvironment())
}

/**
 * A git that is not [available][GitCli.available]: its `--version` fails, as for a missing or broken installation, but
 * it runs the real git for every other call. What a missing executable would stop anyway (every call fails to start)
 * then only the `available` guard of a wrapper stops, which tells a guard from its absence.
 */
internal fun unavailableGit(folder: java.nio.file.Path): GitCli {
    val script = folder.resolve("unavailable-git-${System.nanoTime()}")
    script.toFile().writeText("#!/bin/sh\n[ \"\$1\" = --version ] && exit 1\nexec git \"\$@\"\n")
    script.toFile().setExecutable(true)
    return GitCli(script.toString(), testGitEnvironment()).also { check(!it.available) }
}

/**
 * A git that is [available][GitCli.available] when the [GitCli] is made, and gone after it: its executable is deleted,
 * so that every call fails to start, as for a git that was uninstalled while the app runs.
 */
internal fun vanishedGit(folder: java.nio.file.Path): GitCli {
    val script = folder.resolve("vanished-git-${System.nanoTime()}")
    script.toFile().writeText("#!/bin/sh\nexec git \"\$@\"\n")
    script.toFile().setExecutable(true)
    return GitCli(script.toString(), testGitEnvironment()).also {
        check(it.available)
        java.nio.file.Files
            .delete(script)
    }
}

/**
 * The real git behind a wrapper that appends each call's arguments as a line to [log], to see which commands ran. The
 * calls `GitCli` makes on its own (the version, the environment variables, the fsmonitor setting) are left out.
 */
internal fun recordingGit(
    folder: java.nio.file.Path,
    log: java.nio.file.Path,
): GitCli {
    val script = folder.resolve("recording-git-${System.nanoTime()}")
    script.toFile().writeText(
        FAKE_GIT_START.replace("exit 1;; esac", "exec git \"\$@\";; esac") +
            "printf '%s\\n' \"\$*\" >> '$log'\nexec git \"\$@\"\n",
    )
    script.toFile().setExecutable(true)
    return GitCli(script.toString(), testGitEnvironment())
}

/** The lines [recordingGit] wrote to [log]. */
internal fun recordedCalls(log: java.nio.file.Path): List<String> =
    if (java.nio.file.Files
            .exists(log)
    ) {
        java.nio.file.Files
            .readAllLines(log)
            .filter(String::isNotEmpty)
    } else {
        emptyList()
    }

/**
 * A script in [folder] that stands in for git: [start] and then [body]. The test is skipped when the stand-in cannot
 * run (no `/bin/sh`). The graces are those of [GitCli]: [stopGrace] for a stopped write to end by itself, [pipeGrace]
 * for the output of an ended git to be read (a grandchild that holds the pipe open), [errorGrace] for its standard
 * error. [start] is replaced only by a test of what [FAKE_GIT_START] answers on its own.
 */
internal fun fakeGit(
    folder: java.nio.file.Path,
    body: String,
    pipeGrace: Duration = 300.milliseconds,
    stopGrace: Duration = 2.seconds,
    errorGrace: Duration = 100.milliseconds,
    environment: Map<String, String> = testGitEnvironment(),
    start: String = FAKE_GIT_START,
): GitCli {
    val script = folder.resolve("fake-git-${System.nanoTime()}")
    script.toFile().writeText(start + body + "\n")
    script.toFile().setExecutable(true)
    return GitCli(script.toString(), environment, stopGrace, pipeGrace, errorGrace).also {
        assumeTrue(it.available, "no /bin/sh to stand in for git")
    }
}
