package dev.sluide.git.parse

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile

/**
 * Reads the output of `git diff-tree -r -z --name-status`: a status (`M`, `A`, `D`, `T`, or `R`/`C` with a score
 * such as `R100`) and a path, both NUL-terminated; renames and copies have the old and the new path. Paths are not
 * quoted with `-z`. Other statuses (`U`, `X`) count as modified; an incomplete record at the end is dropped.
 */
internal object GitNameStatusParser {
    fun parse(output: String): List<GitChangedFile> {
        val fields = output.split('\u0000')
        val files = mutableListOf<GitChangedFile>()
        var index = 0
        while (index + 1 < fields.size) {
            val status = fields[index]
            if (status.isEmpty()) break
            val change = changeOf(status[0])
            val paths = if (change == GitChange.RENAMED || change == GitChange.COPIED) 2 else 1
            val record = fields.subList(index + 1, minOf(fields.size, index + 1 + paths))
            if (record.size < paths || record.any(String::isEmpty)) break
            files += if (paths == 2) GitChangedFile(change, record[1], record[0]) else GitChangedFile(change, record[0])
            index += 1 + paths
        }
        return files
    }

    private fun changeOf(status: Char) =
        when (status) {
            'A' -> GitChange.ADDED
            'D' -> GitChange.DELETED
            'R' -> GitChange.RENAMED
            'C' -> GitChange.COPIED
            'T' -> GitChange.TYPE_CHANGED
            else -> GitChange.MODIFIED
        }
}
