package dev.sluide.git

/**
 * A commit in the history of a file (ADR 0046) and how it changed the file there: [file] has the file's path at that
 * commit (before it for a deletion) and, for a rename or copy, the path it came from.
 */
data class GitFileHistoryEntry(
    val commit: GitCommit,
    val file: GitChangedFile,
)

/**
 * One page of the history of a file: up to the asked number of [entries], newest first. [complete] means there are no
 * more after them; a first page that is empty and complete is a file without commits (untracked, or no `HEAD`).
 */
data class GitFileHistoryPage(
    val entries: List<GitFileHistoryEntry>,
    val complete: Boolean,
)

/**
 * Reads the output of `git log --follow --name-status -z` with each commit's format started by U+001E (ADR 0046): a
 * sequence of NUL-terminated fields, per commit its header as [GitLogParser] reads it (U+001E, then the commit's
 * fields), then, if git gives one, the file's status starting with a line break and its one or two paths. The fields
 * are taken in that order, so a subject or path with U+001E, U+001F or a line break in it is read as it is (only NUL
 * cannot be in either). A commit without a status, which git does not give with `--follow` (it lists no merges), would
 * get the path followed at that point, the one the newer entries came from, at first the asked [path]. Reading stops at
 * a field that is no commit where one is expected.
 */
internal object GitFileHistoryParser {
    fun parse(
        output: String,
        path: String,
    ): List<GitFileHistoryEntry> {
        val fields = output.split('\u0000')
        val entries = mutableListOf<GitFileHistoryEntry>()
        var followed = path
        var index = 0
        while (index < fields.size) {
            val header = fields[index]
            if (!header.startsWith(RECORD)) break
            val commit = GitLogParser.parse(header.substring(1)).singleOrNull() ?: break
            index++
            val status = fields.getOrNull(index)?.takeIf { it.startsWith("\n") }?.substring(1)
            var file = GitChangedFile(GitChange.MODIFIED, followed)
            if (status != null) {
                val count = if (status.startsWith('R') || status.startsWith('C')) 2 else 1
                val record = fields.subList(index, minOf(fields.size, index + 1 + count))
                if (record.size < 1 + count) break
                file = GitNameStatusParser
                    .parse((listOf(status) + record.drop(1)).joinToString("\u0000") + "\u0000")
                    .singleOrNull() ?: break
                index += 1 + count
            }
            followed = file.oldPath ?: file.path
            entries += GitFileHistoryEntry(commit, file)
        }
        return entries
    }

    private const val RECORD = '\u001e'
}
