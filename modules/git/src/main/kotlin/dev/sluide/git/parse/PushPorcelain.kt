package dev.sluide.git.parse

import dev.sluide.git.GitPushRejection

/** One status line of `git push --porcelain`: its [flag] (`*` new, `=` up to date, `!` rejected, `-` deleted), [summary]. */
internal class Status(
    val flag: Char,
    val summary: String,
)

/**
 * The status line of deleting [ref] in the porcelain [output]: `:<ref>`, or `(delete):<ref>` when git refused it
 * itself (a failed lease).
 */
internal fun deletionStatus(
    output: String,
    ref: String,
): Status? = status(output, ":$ref") ?: status(output, "(delete):$ref")

/** The status line of the refspec [refs] (`refs/heads/a:refs/heads/b`) in the porcelain [output]. */
internal fun status(
    output: String,
    refs: String,
): Status? =
    output
        .lineSequence()
        .map { it.split('\t', limit = 3) }
        .firstOrNull { it.size == 3 && it[0].length == 1 && it[1] == refs }
        ?.let { Status(it[0][0], it[2]) }

/** Why the remote refused a push: the reason, last in parentheses, of a rejected line's [summary]. */
internal fun rejection(summary: String): GitPushRejection =
    when (summary.substringAfterLast('(', "").removeSuffix(")")) {
        "non-fast-forward", "fetch first" -> GitPushRejection.NON_FAST_FORWARD
        "stale info" -> GitPushRejection.STALE
        else -> GitPushRejection.OTHER
    }
