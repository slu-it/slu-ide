package dev.sluide.git.parse

import dev.sluide.git.GitPushRejection

/**
 * One status line of `git push --porcelain`: its [flag] (`*` new, `=` up to date, `!` rejected, `-` deleted) and
 * [summary].
 */
internal class PushStatus(
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
): PushStatus? = pushStatus(output, ":$ref") ?: pushStatus(output, "(delete):$ref")

/** The status line of the refspec [refs] (`refs/heads/a:refs/heads/b`) in the porcelain [output]. */
internal fun pushStatus(
    output: String,
    refs: String,
): PushStatus? =
    output
        .lineSequence()
        .map { it.split('\t', limit = 3) }
        .firstOrNull { it.size == 3 && it[0].length == 1 && it[1] == refs }
        ?.let { PushStatus(it[0][0], it[2]) }

/** Why the remote refused a push: the reason, last in parentheses, of a rejected line's [summary]. */
internal fun rejection(summary: String): GitPushRejection =
    when (reason(summary)) {
        "non-fast-forward", "fetch first" -> GitPushRejection.NON_FAST_FORWARD
        "stale info" -> GitPushRejection.STALE
        else -> GitPushRejection.OTHER
    }

/** The reason, last in parentheses, of a status line's [summary]: `already exists` of `[rejected] (already exists)`. */
internal fun reason(summary: String): String = summary.substringAfterLast('(', "").removeSuffix(")")
