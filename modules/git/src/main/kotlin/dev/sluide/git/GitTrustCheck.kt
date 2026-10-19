package dev.sluide.git

import dev.sluide.git.cli.ConfigEntry
import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.configEntries
import dev.sluide.git.cli.gitPaths
import dev.sluide.git.cli.gitlinks
import dev.sluide.git.cli.shown
import dev.sluide.git.cli.topLevel
import dev.sluide.projecttrust.ProjectTrustCheck
import dev.sluide.projecttrust.TrustFinding
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * What a repository's own configuration would make git run (ADR 0053): entries of its local configuration (with
 * `include` and `includeIf` files) that are not known to be harmless, and its hooks; the same for its submodules, whose
 * configuration `git status` uses when it looks into them. Git's own configuration of the user (global, system, the
 * environment) is the user's and not reported.
 *
 * Each check records what it read in the repositories' git folders and in included files, its [inputs], so that the
 * window's status can tell from their stamp whether a change of `.git` may change what the check finds (ADR 0062).
 */
@Component
class GitTrustCheck(
    private val git: GitCli,
) : ProjectTrustCheck {
    override val id = ID

    override val name = "Git"

    /**
     * What the check that started last read, per folder; shared by the windows (a singleton bean), whose checks may
     * run at once, so a slower check that started earlier does not replace what a later one read.
     */
    private val inputsByFolder = ConcurrentHashMap<Path, Recorded>()

    /** Numbers the checks in the order they start. */
    private val checks = AtomicLong()

    override fun check(folder: Path): List<TrustFinding>? {
        val top = folder.toAbsolutePath().normalize()
        val sequence = checks.incrementAndGet()
        if (!git.available || !Files.exists(folder.resolve(".git"))) {
            record(top, Recorded(sequence, null))
            return emptyList()
        }
        val budget = Budget()
        val findings = repositoryFindings(top, top, "", 0, budget)
        val inputs =
            budget.inputs.takeIf { findings != null && it.complete }?.let {
                GitTrustInputs(
                    files = it.files.toSet(),
                    folders = it.folders.toSet(),
                    redirects = it.redirects.toSet(),
                    large = it.large.toSet(),
                )
            }
        record(top, Recorded(sequence, inputs))
        return findings
    }

    /** What a check that started as the [sequence]th read; `null` inputs if it could not name them. */
    private class Recorded(
        val sequence: Long,
        val inputs: GitTrustInputs?,
    )

    /** Keeps [recorded] for [top] unless a check that started later has recorded already (windows check in parallel). */
    private fun record(
        top: Path,
        recorded: Recorded,
    ) {
        inputsByFolder.merge(top, recorded) { old, new -> if (old.sequence > new.sequence) old else new }
    }

    /**
     * What the last check of [folder] read that can change what it finds (ADR 0062): per repository (the opened one and
     * its submodules) its configuration files (`config`, `config.worktree`, every file an entry of the repository's
     * scopes comes from, and every file an `include.path` or `includeIf.*.path` names, also one that does not exist or
     * apply yet), `HEAD` (`includeIf "onbranch:"`), `commondir` and `gitdir` (where a linked worktree's git folder
     * leads), the index (the submodules are read from it), the hooks folder, and the `.git` of the repository and of
     * each submodule's folder (a folder, a `gitdir:` file or a link). `null` before a check, or if the last one could
     * not check or could not name a file (an include path of another user's home).
     */
    fun inputs(folder: Path): GitTrustInputs? = inputsByFolder[folder.toAbsolutePath().normalize()]?.inputs

    /** How many more repositories (the opened one and submodules) a check may look at, and what the check read. */
    private class Budget(
        var repositories: Int = MAX_REPOSITORIES,
        val inputs: Inputs = Inputs(),
    )

    /** What a check read, collected while it runs; not [complete] if a file it depends on could not be named. */
    private class Inputs {
        val files = mutableSetOf<Path>()
        val large = mutableSetOf<Path>()
        val folders = mutableSetOf<Path>()
        val redirects = mutableSetOf<Path>()
        var complete = true
    }

    /**
     * The findings of the repository at [root] and its submodules, shown with [prefix] (the path of a submodule) and
     * paths below the opened folder [top] relative to it;
     * `null` if one of them could not be checked, e.g. git refuses the repository as not the user's (`safe.directory`),
     * submodules are nested more than [MAX_SUBMODULE_DEPTH] deep or there are more than [MAX_REPOSITORIES].
     */
    private fun repositoryFindings(
        top: Path,
        root: Path,
        prefix: String,
        depth: Int,
        budget: Budget,
    ): List<TrustFinding>? {
        if (--budget.repositories < 0) return null
        val entries = git.configEntries(root) ?: return null
        // Also a check that git sees the repository: the configuration alone it lists for any folder.
        val gitPaths = git.gitPaths(root, GIT_PATHS)?.map { it.toAbsolutePath().normalize() } ?: return null
        val hooksFolder = gitPaths.first()
        // The index by its attributes: it is large, and its gitlinks are read anew by every check.
        val index = gitPaths[GIT_PATHS.indexOf("index")]
        budget.inputs.large.add(index)
        budget.inputs.files.addAll(gitPaths.drop(1).filter { it != index })
        budget.inputs.folders.add(hooksFolder)
        budget.inputs.redirects.add(root.resolve(".git"))
        recordConfigFiles(root, entries, budget.inputs)
        // Hooks of a hooks folder the user set up for all repositories are the user's, unless it is the repository's
        // own (a relative hooks path).
        val hooksPathScope = entries.lastOrNull { it.key.equals("core.hookspath", ignoreCase = true) }?.scope
        val hooks =
            if (hooksPathScope in USER_SCOPES && !hooksFolder.startsWith(root)) {
                emptyList()
            } else {
                hooks(root, hooksFolder) ?: return null
            }
        // A work tree elsewhere (core.worktree) is where git reads and writes files: harmless only if it is the folder.
        val worktreeElsewhere =
            entries.any { it.scope in REPOSITORY_SCOPES && it.key.equals("core.worktree", ignoreCase = true) } &&
                git.topLevel(root).let { it == null || it != runCatching { root.toRealPath() }.getOrNull() }
        val own = (configFindings(entries, top, worktreeElsewhere) + hooks).map { it.inSubmodule(prefix) }
        // Paths of the index below the repository only: a crafted index may name others.
        val submodules =
            git.gitlinks(root)?.filter { path ->
                val folder = root.resolve(path).normalize()
                val inside = folder.startsWith(root) && folder != root
                if (inside) budget.inputs.redirects.add(folder.resolve(".git"))
                inside && Files.exists(folder.resolve(".git"))
            } ?: return null
        if (submodules.isNotEmpty() && depth >= MAX_SUBMODULE_DEPTH) return null
        return own +
            submodules.flatMap { path ->
                repositoryFindings(top, root.resolve(path).normalize(), "$prefix$path/", depth + 1, budget)
                    ?: return null
            }
    }

    /**
     * Records the files the configuration [entries] of the repository at [root] come from, and those its includes name
     * (relative to the file that includes them, `~/` in the user's home), whether they exist and apply or not.
     */
    private fun recordConfigFiles(
        root: Path,
        entries: List<ConfigEntry>,
        inputs: Inputs,
    ) {
        for (entry in entries.filter { it.scope in REPOSITORY_SCOPES }) {
            val origin = entry.origin.takeIf { it.startsWith("file:") }?.removePrefix("file:")
            val originFile = origin?.let { resolvedPath(root, it) }
            if (origin != null && originFile == null) inputs.complete = false
            originFile?.let { inputs.files.add(it) }
            if (!INCLUDE_PATH.matches(entry.key)) continue
            val value = entry.value
            val included =
                when {
                    value == null || originFile == null -> null
                    value.startsWith("~/") -> resolvedPath(Path.of(System.getProperty("user.home")), value.drop(2))
                    value.startsWith("~") -> null
                    else -> resolvedPath(originFile.parent ?: root, value)
                }
            if (included == null) inputs.complete = false else inputs.files.add(included)
        }
    }

    companion object {
        /** The id git's tools ask [dev.sluide.projecttrust.ProjectTrust.allows] with. */
        const val ID = "git"

        /** What `git rev-parse --git-path` is asked for: the hooks folder first, then files the check depends on. */
        private val GIT_PATHS = listOf("hooks", "config", "config.worktree", "index", "HEAD", "commondir", "gitdir")

        /** The keys of includes: `include.path`, `includeif.<condition>.path` (sections lower case, as git lists them). */
        private val INCLUDE_PATH = Regex("include\\.path|includeif\\..*\\.path")

        /** How deep submodules of submodules are checked; deeper ones cannot be. */
        private const val MAX_SUBMODULE_DEPTH = 5

        /** How many repositories, the opened one and its submodules, are checked at most; with more, none can be. */
        private const val MAX_REPOSITORIES = 50
    }
}

private val USER_SCOPES = setOf("system", "global", "command")

private val REPOSITORY_SCOPES = setOf("local", "worktree")

/**
 * Keys of a repository's configuration that make Thoreau's git run nothing the repository defines: what `git clone`,
 * `git init`, `git lfs install`, `git sparse-checkout` and everyday commands write, and what Thoreau's git never uses
 * (`core.pager`: its output goes to a pipe; `core.editor`: Thoreau sets `GIT_EDITOR`). `*` stands for a subsection.
 */
private val HARMLESS_KEYS =
    setOf(
        "core.repositoryformatversion",
        "core.filemode",
        "core.bare",
        "core.logallrefupdates",
        "core.ignorecase",
        "core.precomposeunicode",
        "core.symlinks",
        "core.autocrlf",
        "core.eol",
        "core.safecrlf",
        "core.checkstat",
        "core.trustctime",
        "core.untrackedcache",
        "core.quotepath",
        "core.commentchar",
        "core.commentstring",
        "core.longpaths",
        "core.sparsecheckout",
        "core.sparsecheckoutcone",
        "core.editor",
        "core.pager",
        "extensions.objectformat",
        "extensions.refstorage",
        "extensions.worktreeconfig",
        "remote.*.url",
        "remote.*.pushurl",
        "remote.*.fetch",
        "remote.*.push",
        "remote.*.tagopt",
        "remote.*.prune",
        "remote.*.prunetags",
        "remote.*.mirror",
        "remote.*.skipfetchall",
        "branch.*.remote",
        "branch.*.merge",
        "branch.*.rebase",
        "branch.*.pushremote",
        "branch.*.description",
        "user.name",
        "user.email",
        "user.signingkey",
        "commit.gpgsign",
        "tag.gpgsign",
        "pull.rebase",
        "pull.ff",
        "push.default",
        "push.autosetupremote",
        "fetch.prune",
        "fetch.prunetags",
        "init.defaultbranch",
        "submodule.active",
        "submodule.*.url",
        "submodule.*.active",
        "lfs.repositoryformatversion",
        "lfs.*.access",
        "lfs.*.locksverify",
        "gc.auto",
    )

/** Sections whose every key is harmless in the same sense: aliases and pagers, which Thoreau's git never uses. */
private val HARMLESS_SECTIONS = setOf("alias", "pager")

/** Values of keys that are harmless only with them: git's own fsmonitor daemon, and Git LFS as it installs itself. */
private val HARMLESS_VALUES =
    mapOf(
        "core.fsmonitor" to setOf("true", "false", "yes", "no", "on", "off", "1", "0", ""),
        "filter.lfs.clean" to setOf("git-lfs clean -- %f"),
        "filter.lfs.smudge" to setOf("git-lfs smudge -- %f"),
        "filter.lfs.process" to setOf("git-lfs filter-process"),
        "filter.lfs.required" to setOf("true"),
    )

/** When git runs what a key names; keys not listed are reported without a note. */
private val KEY_NOTES =
    mapOf(
        "core.fsmonitor" to "runs on every git status",
        "core.hookspath" to "git runs its hooks from there",
        "core.worktree" to "git reads and writes the files there, also on checkout and reset",
        "core.sshcommand" to "runs on fetch and push",
        "core.gitproxy" to "runs on fetch",
        "core.askpass" to "runs when git asks for a password",
        "sequence.editor" to "runs when git edits a list of commits",
        "credential.helper" to "runs on fetch and push",
        "credential.*.helper" to "runs on fetch and push",
        "filter.*.clean" to "runs on every git status, for the files .gitattributes names",
        "filter.*.smudge" to "runs on checkout and for the editor's diff",
        "filter.*.process" to "runs on git status, checkout and the editor's diff",
        "diff.external" to "runs when git shows a diff",
        "diff.*.command" to "runs when git shows a diff",
        "diff.*.textconv" to "runs when git shows a diff",
        "merge.*.driver" to "runs on cherry-picks and reverts",
        "gpg.program" to "runs when git signs or verifies",
        "gpg.*.program" to "runs when git signs or verifies",
        "remote.*.uploadpack" to "runs on fetch",
        "remote.*.receivepack" to "runs on push",
        "remote.*.vcs" to "runs on fetch and push",
        "protocol.allow" to "allows transports that run commands",
        "protocol.*.allow" to "allows transports that run commands",
        "remote.*.promisor" to "fetches missing objects from the remote on reads",
        "extensions.partialclone" to "fetches missing objects from the remote on reads",
        "include.path" to "reads more configuration from there",
        "includeif.*.path" to "reads more configuration from there",
    )

/** When git runs a hook; hooks not listed run when git starts them. */
private val HOOK_NOTES =
    mapOf(
        "pre-commit" to "runs on commit",
        "prepare-commit-msg" to "runs on commit",
        "commit-msg" to "runs on commit",
        "post-commit" to "runs on commit",
        "post-checkout" to "runs on checkout",
        "post-merge" to "runs on merges",
        "pre-push" to "runs on push",
        "post-rewrite" to "runs when commits are rewritten",
        "reference-transaction" to "runs whenever a branch or tag changes",
        "post-index-change" to "runs whenever git writes the index",
        "pre-auto-gc" to "runs on git's automatic cleanup",
    )

/**
 * The key [key] as a pattern of the key lists: section and variable lower case, a subsection (case kept by git) as
 * `*`.
 */
private fun pattern(key: String): String {
    val section = key.substringBefore('.').lowercase()
    val variable = key.substringAfterLast('.').lowercase()
    return if (key.indexOf('.') == key.lastIndexOf('.')) "$section.$variable" else "$section.*.$variable"
}

/** The entries of the repository's configuration that are not known to be harmless, with when they run if known. */
internal fun configFindings(
    entries: List<ConfigEntry>,
    top: Path? = null,
    worktreeElsewhere: Boolean = true,
): List<TrustFinding> =
    entries
        .filter { it.scope in REPOSITORY_SCOPES }
        .filterNot { entry ->
            val pattern = pattern(entry.key)
            pattern in HARMLESS_KEYS ||
                (pattern == "core.worktree" && !worktreeElsewhere) ||
                entry.key.substringBefore('.').lowercase() in HARMLESS_SECTIONS ||
                // Compared as git compares booleans: ignoring case, not spaces.
                HARMLESS_VALUES[entry.key.lowercase()]?.contains(entry.value?.lowercase() ?: "true") == true
        }.map { entry ->
            val origin = shown(entry.origin.removePrefix("file:"), top)
            val text = if (entry.value == null) entry.key else "${entry.key} = ${entry.value}"
            TrustFinding(
                "$text ($origin)",
                KEY_NOTES[pattern(entry.key)],
                "config\u0000$origin\u0000${entry.key}" + (entry.value?.let { "\u0000=$it" } ?: ""),
            )
        }

/**
 * The hooks in [hooksFolder] (relative paths shown relative to [root]): files git could run, not its `.sample`s,
 * identified by their content; `null` if the folder cannot be read.
 */
internal fun hooks(
    root: Path,
    hooksFolder: Path,
): List<TrustFinding>? {
    if (!hooksFolder.isDirectory()) return emptyList()
    return try {
        Files.list(hooksFolder).use { files ->
            files
                .filter { !it.name.startsWith(".") && !it.name.endsWith(".sample") }
                .filter { Files.isRegularFile(it) && Files.isExecutable(it) }
                .sorted()
                .toList()
                .map { hook ->
                    val shown = if (hook.startsWith(root)) root.relativize(hook) else hook
                    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(hook))
                    TrustFinding(
                        "hook ${hook.name} ($shown)",
                        HOOK_NOTES[hook.name] ?: "runs when git starts it",
                        "hook\u0000$shown\u0000${digest.joinToString("") { "%02x".format(it) }}",
                    )
                }
        }
    } catch (_: IOException) {
        null
    }
}

/** [path] resolved against [base], absolute and normalized; `null` if it cannot be a path. */
private fun resolvedPath(
    base: Path,
    path: String,
): Path? = runCatching { base.resolve(path).toAbsolutePath().normalize() }.getOrNull()

/** The finding as one of the submodule [prefix] (its path and `/`), if any. */
private fun TrustFinding.inSubmodule(prefix: String) =
    if (prefix.isEmpty()) {
        this
    } else {
        TrustFinding(
            "submodule ${prefix.dropLast(1)}: $text",
            note,
            "$prefix\u0000$fingerprint",
        )
    }

/**
 * A file git names absolutely (as in submodules) shown relative to the opened folder [top] if it is below it; git names
 * it by its real path (on macOS `/private/var/…` for `/var/…`), so below [top]'s real path counts too.
 */
private fun shown(
    file: String,
    top: Path?,
): String {
    val path = runCatching { Path.of(file) }.getOrNull()?.takeIf { it.isAbsolute }?.normalize() ?: return file
    val base =
        top?.takeIf { path.startsWith(it) }
            ?: top?.let { runCatching { it.toRealPath() }.getOrNull() }?.takeIf { path.startsWith(it) }
    return base?.relativize(path)?.toString() ?: file
}
