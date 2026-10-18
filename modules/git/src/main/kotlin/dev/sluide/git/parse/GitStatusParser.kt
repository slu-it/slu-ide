package dev.sluide.git.parse

import dev.sluide.git.GitHead
import dev.sluide.git.GitStatus

/**
 * Reads the output of `git status --porcelain=v2 --branch -z --ignored`: NUL-separated records with paths relative
 * to the repository root, `/`-separated. A path ending in `/` stands for a whole untracked or ignored folder, which git
 * reports without listing its content.
 */
internal object GitStatusParser {
    /**
     * The checked-out ref from the `# branch.oid` and `# branch.head` headers of [output], or `null` if there is no
     * `# branch.head` header. Other headers (upstream, ahead/behind) are ignored.
     */
    fun parseHead(output: String): GitHead? {
        // Headers come first; stop at the first other record, e.g. a renamed file's original path.
        val headers =
            output
                .splitToSequence('\u0000')
                .takeWhile { it.startsWith("# ") }
                .map { it.removePrefix("# ") }
                .associate { it.substringBefore(' ') to it.substringAfter(' ') }
        val head = headers["branch.head"] ?: return null
        return GitHead(head.takeIf { it != "(detached)" }, headers["branch.oid"]?.takeIf { it != "(initial)" })
    }

    fun parse(output: String): Map<String, GitStatus> {
        val statuses = LinkedHashMap<String, GitStatus>()
        val records = output.split('\u0000').iterator()
        while (records.hasNext()) {
            val record = records.next()
            when (record.firstOrNull()) {
                // 1 XY sub mH mI mW hH hI path
                '1' -> {
                    val fields = record.split(' ', limit = 9)
                    // Index A: added; worktree A: intent to add (`git add -N`), not in the index yet.
                    if (fields.size == 9) {
                        statuses[fields[8]] = if ('A' in fields[1]) GitStatus.ADDED else GitStatus.MODIFIED
                    }
                }

                // 2 XY sub mH mI mW hH hI Xscore path, then the original path as the next record
                '2' -> {
                    val fields = record.split(' ', limit = 10)
                    if (fields.size == 10) statuses[fields[9]] = GitStatus.ADDED
                    if (records.hasNext()) records.next()
                }

                // u XY sub m1 m2 m3 mW h1 h2 h3 path: a merge conflict
                'u' -> {
                    val fields = record.split(' ', limit = 11)
                    if (fields.size == 11) statuses[fields[10]] = GitStatus.MODIFIED
                }

                // ? path, ! path
                '?' -> {
                    if (record.length > 2) statuses[record.substring(2)] = GitStatus.UNVERSIONED
                }

                '!' -> {
                    if (record.length > 2) statuses[record.substring(2)] = GitStatus.IGNORED
                }

                // Empty records (the output ends with NUL) and headers (#, see parseHead) carry no file status.
                else -> {}
            }
        }
        return statuses
    }
}
