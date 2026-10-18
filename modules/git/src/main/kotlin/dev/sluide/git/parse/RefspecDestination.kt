package dev.sluide.git.parse

/**
 * Where a fetch refspec writes: below [prefix] for a [glob] (the part before its `*`, e.g. `refs/remotes/origin/`;
 * the `*` matches any rest, also with slashes), else the ref [prefix] itself.
 */
internal data class RefspecDestination(
    val prefix: String,
    val glob: Boolean,
) {
    fun matches(name: String) = if (glob) name.startsWith(prefix) else name == prefix
}

/**
 * Where the fetch [refspecs] (`remote.<name>.fetch`) write: their right-hand sides, without the `+`; negative refspecs
 * (`^…`) and those without a destination (only `FETCH_HEAD`) write nothing.
 */
internal fun refspecDestinations(refspecs: List<String>): List<RefspecDestination> =
    refspecs
        .map { it.trim().removePrefix("+") }
        .filter { !it.startsWith('^') && ':' in it }
        .map { it.substringAfter(':') }
        .filter { it.startsWith("refs/") }
        .map { destination ->
            if ('*' in destination) {
                RefspecDestination(destination.substringBefore('*'), glob = true)
            } else {
                RefspecDestination(destination, glob = false)
            }
        }
