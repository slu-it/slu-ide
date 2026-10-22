package dev.sluide.githubactions

/** The parser's JSON for a workflow in `src/test/resources/dev/sluide/githubactions/` (see its README). */
internal fun sampleJson(name: String): String =
    requireNotNull(Samples::class.java.getResource("/dev/sluide/githubactions/$name.json")) { "no sample $name" }
        .readText()

private object Samples
