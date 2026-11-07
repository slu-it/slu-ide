package dev.sluide.languageservers.files

import dev.sluide.languageservers.FileChange
import dev.sluide.languageservers.FileChangeKind
import dev.sluide.languageservers.LspUris
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.WatchKind
import java.io.File
import java.nio.file.Path

/**
 * What a server's file watchers (`workspace/didChangeWatchedFiles` registrations) want to hear: a change matches if a
 * watcher's glob matches its path (a plain pattern the absolute path, a relative pattern the path below its base) and
 * the watcher's kinds (create, change, delete bits; all if left out) include the change's kind.
 */
internal class WatcherFilter(
    watchers: List<FileSystemWatcher>,
) {
    private class Rule(
        val base: Path?,
        val glob: Regex,
        val kinds: Int,
    )

    private val rules =
        watchers.mapNotNull { watcher ->
            val pattern = watcher.globPattern ?: return@mapNotNull null
            val kinds = watcher.kind ?: ALL_KINDS
            if (pattern.isLeft) {
                Globs.regex(pattern.left ?: return@mapNotNull null)?.let { Rule(null, it, kinds) }
            } else {
                val relative = pattern.right ?: return@mapNotNull null
                val baseUri = relative.baseUri?.let { if (it.isLeft) it.left?.uri else it.right }
                val base = baseUri?.let(LspUris::toPath) ?: return@mapNotNull null
                Globs.regex(relative.pattern ?: return@mapNotNull null)?.let { Rule(base, it, kinds) }
            }
        }

    /** Whether no watcher wants anything. */
    val isEmpty: Boolean get() = rules.isEmpty()

    fun matches(change: FileChange): Boolean {
        val path = change.path.toAbsolutePath().normalize()
        val bit = kindBit(change.kind)
        return rules.any { rule ->
            if (rule.kinds and bit == 0) return@any false
            val subject =
                if (rule.base == null) {
                    path.slashed()
                } else {
                    if (!path.startsWith(rule.base)) return@any false
                    rule.base.relativize(path).slashed()
                }
            rule.glob.matches(subject)
        }
    }

    private fun Path.slashed() = toString().replace(File.separatorChar, '/')

    private fun kindBit(kind: FileChangeKind) =
        when (kind) {
            FileChangeKind.CREATED -> WatchKind.Create
            FileChangeKind.CHANGED -> WatchKind.Change
            FileChangeKind.DELETED -> WatchKind.Delete
        }

    private companion object {
        const val ALL_KINDS = WatchKind.Create or WatchKind.Change or WatchKind.Delete
    }
}
