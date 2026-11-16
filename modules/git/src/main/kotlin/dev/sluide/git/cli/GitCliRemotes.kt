package dev.sluide.git.cli

import dev.sluide.git.GitResult
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val REMOTES = "refs/remotes/"

/**
 * The configuration entries of the repository at [root] whose key matches [pattern] (`git config -z --get-regexp`),
 * as key (section and variable lower case, the name between them as written) and value, in the order git reads them;
 * `null` if git is not available, fails or takes longer than 3 s. An entry without `=` has no value and is left out.
 */
internal fun GitCli.configMatching(
    root: Path,
    pattern: String,
): List<Pair<String, String>>? {
    if (!available) return null
    val arguments = listOf("--no-optional-locks", "config", "-z", "--get-regexp", pattern)
    val execution = execute(arguments, root, 3.seconds, null, write = false) ?: return null
    if (execution.exitValue == 1) return emptyList()
    if (execution.exitValue != 0 || !execution.complete) return null
    return execution.output
        .toString(Charsets.UTF_8)
        .split('\u0000')
        .filter { '\n' in it }
        .map { it.substringBefore('\n') to it.substringAfter('\n') }
}

/**
 * The full names of the refs below `refs/remotes/` of the repository at [root]; `null` if git is not available,
 * fails or takes longer than 10 s.
 */
internal fun GitCli.remoteRefNames(root: Path): List<String>? {
    if (!available) return null
    val arguments = listOf("--no-optional-locks", "for-each-ref", "--format=%(refname)", REMOTES)
    return run(arguments, root, 10.seconds)?.lines()?.filter(String::isNotEmpty)
}

/** Removes every `remote.<name>.pushurl`; succeeds also if there is none (git exits 5 then). */
internal fun GitCli.unsetPushUrls(
    root: Path,
    name: String,
): GitResult {
    val (result, exit) = writeWithExit(root, listOf("config", "--unset-all", "remote.$name.pushurl"))
    return if (exit == 5) GitResult(true, "") else result
}

/**
 * Whether git rewrites all the fetch [refspecs] of the remote [old] when it renames it: it leaves a negative one and
 * one whose destination is not below `refs/remotes/<old>/` (also none) as it is (measured with git 2.56.1).
 */
internal fun refspecsFollowRename(
    refspecs: List<String>,
    old: String,
): Boolean =
    refspecs.all { spec ->
        !spec.startsWith('^') && spec.removePrefix("+").substringAfter(':', "").startsWith("$REMOTES$old/")
    }

/**
 * Whether fetching with [refspecs] writes [ref], as `git remote remove` sees it: a positive refspec whose destination
 * covers it (a `*` matches any rest), unless a negative refspec excludes any of the sources the refspecs would fetch it
 * from (measured with git 2.56.1: a ref kept by one source stays although another, not negated, writes it too).
 */
internal fun refspecsMap(
    refspecs: List<String>,
    ref: String,
): Boolean {
    val negatives = refspecs.filter { it.startsWith('^') }.map { it.drop(1) }
    return refspecs
        .filter { !it.startsWith('^') && ':' in it }
        .mapNotNull { spec ->
            val pair = spec.removePrefix("+")
            sourceOf(pair.substringBefore(':'), pair.substringAfter(':'), ref)
        }.let { sources ->
            sources.isNotEmpty() &&
                sources.none { source -> negatives.any { matchesPattern(it, source) } }
        }
}

/** The source that [source]:[destination] fetches into [ref], or `null` if it does not write it. */
private fun sourceOf(
    source: String,
    destination: String,
    ref: String,
): String? {
    if ('*' !in destination) return source.takeIf { ref == destination }
    if ('*' !in source) return null
    val prefix = destination.substringBefore('*')
    val suffix = destination.substringAfter('*')
    if (ref.length < prefix.length + suffix.length || !ref.startsWith(prefix) || !ref.endsWith(suffix)) return null
    return source.replaceFirst("*", ref.substring(prefix.length, ref.length - suffix.length))
}

private fun matchesPattern(
    pattern: String,
    name: String,
): Boolean {
    if ('*' !in pattern) return pattern == name
    val prefix = pattern.substringBefore('*')
    val suffix = pattern.substringAfter('*')
    return name.length >= prefix.length + suffix.length && name.startsWith(prefix) && name.endsWith(suffix)
}

/**
 * The refs below `refs/remotes/` that `git remote remove` deletes for the remote [name] (measured with git 2.56.1):
 * those its refspecs write ([refspecsMap]) minus those another remote's refspecs write too. The symbolic
 * `<name>/HEAD` is also deleted when the ref it points at is, which this does not tell: callers leave it out of the
 * count. [fetchSpecs] are the fetch refspecs by remote, [refs] the refs ([remoteRefNames]).
 */
internal fun removedRefs(
    name: String,
    fetchSpecs: Map<String, List<String>>,
    refs: List<String>,
): Set<String> {
    val own = fetchSpecs[name].orEmpty()
    val others = fetchSpecs.filterKeys { it != name }.values
    return refs.filterTo(mutableSetOf()) { ref ->
        refspecsMap(own, ref) && others.none { other -> refspecsMap(other, ref) }
    }
}
