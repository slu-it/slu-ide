package dev.sluide.git

/**
 * Reads the output of `git status --porcelain=v2 -z --ignored`: NUL-separated records with paths relative to the
 * repository root, `/`-separated. A path ending in `/` stands for a whole untracked or ignored folder, which git
 * reports without listing its content.
 */
internal object GitStatusParser {
    fun parse(output: String): Map<String, GitStatus> {
        val statuses = LinkedHashMap<String, GitStatus>()
        val records = output.split('\u0000').iterator()
        while (records.hasNext()) {
            val record = records.next()
            when (record.firstOrNull()) {
                // 1 XY sub mH mI mW hH hI path
                '1' -> {
                    val fields = record.split(' ', limit = 9)
                    if (fields.size == 9) {
                        statuses[fields[8]] = if (fields[1].first() == 'A') GitStatus.ADDED else GitStatus.MODIFIED
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

                '?' -> {
                    statuses[record.substring(2)] = GitStatus.UNVERSIONED
                }

                '!' -> {
                    statuses[record.substring(2)] = GitStatus.IGNORED
                }

                // Empty records (the output ends with NUL) and headers (#) carry no file status.
                else -> {}
            }
        }
        return statuses
    }
}
