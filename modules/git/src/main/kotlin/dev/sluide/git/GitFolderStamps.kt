package dev.sluide.git

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat

/**
 * What git's trust check (ADR 0053) read in the repository of a folder and its submodules, as recorded by
 * [GitTrustCheck] for the stamp of [GitFolderStamps.trust] (ADR 0062): [files] by their content and permissions
 * (configuration, included files, `HEAD`, `commondir`, `gitdir`), [large] by their size, time and inode only (the index),
 * [folders] by the content and permissions of each entry (hooks), [redirects] by what they point to (the `.git` of each
 * repository: a folder, a `gitdir:` file or a link).
 */
class GitTrustInputs internal constructor(
    internal val files: Set<Path>,
    internal val folders: Set<Path>,
    internal val redirects: Set<Path>,
    internal val large: Set<Path> = emptySet(),
)

/**
 * Cheap stamps of what git's state in a repository depends on (ADR 0062). The state is stamped by times, sizes and
 * inodes of files, no content: a file rewritten by git gets a new inode (git writes a lock file and renames it), so even a
 * rewrite within the file system's time resolution changes the stamp. What the trust check reads is security-relevant
 * and small, so it is stamped by content (SHA-256, up to [MAX_HASHED] bytes) and permissions, which neither an edit in
 * place with the old time (`touch -r`) nor a `chmod +x` escapes. `null` where a stamp cannot be computed, which callers
 * take as "changed".
 */
internal object GitFolderStamps {
    /** Files directly in the git folder that the status, the log and its refs read. */
    private val STATE_FILES =
        listOf(
            "HEAD",
            "index",
            "packed-refs",
            "MERGE_HEAD",
            "MERGE_MSG",
            "CHERRY_PICK_HEAD",
            "REVERT_HEAD",
            "ORIG_HEAD",
            "FETCH_HEAD",
            "AUTO_MERGE",
            "shallow",
            "commondir",
            "config",
            "config.worktree",
            "info/exclude",
            "info/attributes",
            "info/sparse-checkout",
            "info/grafts",
            "objects/info/alternates",
        )

    /** Folders of the git folder walked with everything in them: refs, what git is in the middle of, submodules. */
    private val STATE_FOLDERS = listOf("refs", "reftable", "rebase-merge", "rebase-apply", "sequencer", "modules")

    /** Files of the trust inputs larger than this are stamped by their size, time and inode, not their content. */
    const val MAX_HASHED = 1 shl 20

    /** Above this many entries of hooks folders, or this many bytes read, the trust inputs have no stamp. */
    const val MAX_ENTRIES = 256
    const val MAX_READ = 16L shl 20

    /** Folders below [STATE_FOLDERS] that no read depends on and that may be large: objects and reflogs of submodules. */
    private val SKIPPED_FOLDERS = setOf("objects", "logs", "lfs")

    /**
     * The stamp of what `git status`, the git log and its refs read in the `.git` folder of [root]: `HEAD`, the index,
     * the refs (loose, packed or in a reftable), what git is in the middle of (merge, cherry-pick, revert, rebase, sequencer), the
     * configuration, `info/` and the submodules' git folders; not objects or reflogs. `null` for a `.git` file (linked
     * worktrees, submodules), whose git folder is not resolved here, or if it cannot be read.
     */
    fun state(root: Path): String? {
        val gitFolder = root.resolve(".git")
        if (!Files.isDirectory(gitFolder, LinkOption.NOFOLLOW_LINKS)) return null
        return stamp { digest ->
            STATE_FILES.forEach { digest.file(gitFolder.resolve(it)) }
            STATE_FOLDERS.forEach { digest.tree(gitFolder.resolve(it)) }
        }
    }

    /**
     * The stamp of the [inputs] of git's trust check; `null` without them, if they cannot be read, or beyond
     * [maxEntries] entries of hooks folders or [maxRead] bytes read in all (then the check runs on every change).
     */
    fun trust(
        inputs: GitTrustInputs?,
        maxEntries: Int = MAX_ENTRIES,
        maxRead: Long = MAX_READ,
    ): String? {
        if (inputs == null) return null
        return stamp(maxEntries, maxRead) { digest ->
            inputs.files.sorted().forEach { digest.content(it) }
            inputs.large.sorted().forEach { digest.file(it) }
            inputs.folders.sorted().forEach { digest.listing(it) }
            inputs.redirects.sorted().forEach { digest.redirect(it) }
        }
    }

    private fun stamp(
        maxEntries: Int = Int.MAX_VALUE,
        maxRead: Long = Long.MAX_VALUE,
        fill: (Stamp) -> Unit,
    ): String? =
        try {
            val stamp = Stamp(maxEntries, maxRead)
            fill(stamp)
            stamp.hex()
        } catch (_: IOException) {
            null
        }

    /** Thrown when a stamp would read more than its limits allow. */
    private class TooMuch : IOException("Too much to stamp")

    /**
     * Collects entries of a stamp; throws [IOException] for anything that cannot be read, except a missing file, and
     * beyond [maxEntries] entries of listings or [maxRead] bytes read.
     */
    private class Stamp(
        private val maxEntries: Int,
        private val maxRead: Long,
    ) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var entries = 0
        private var read = 0L

        fun hex(): String = HexFormat.of().formatHex(digest.digest())

        private fun add(
            path: Path,
            text: String,
        ) {
            digest.update("$path\u0000$text\n".toByteArray())
        }

        private fun attributes(attributes: BasicFileAttributes) =
            "${attributes.size()}\u0000${attributes.lastModifiedTime().toInstant()}\u0000${attributes.fileKey()}"

        /** A file by its attributes, following links (a hook may be a link into the work tree). */
        fun file(path: Path) {
            val read =
                try {
                    Files.readAttributes(path, BasicFileAttributes::class.java)
                } catch (_: NoSuchFileException) {
                    return add(path, "-")
                }
            add(path, (if (read.isDirectory) "d" else "f") + attributes(read))
        }

        /**
         * A file by its content (SHA-256; above [MAX_HASHED] bytes by its attributes) and permissions, following links
         * (a hook may be a link into the work tree). Only a regular file is opened: a FIFO would block the reading
         * thread, a device such as `/dev/zero` never end; they and anything else count by their type and attributes.
         */
        fun content(path: Path) {
            val attributes =
                try {
                    Files.readAttributes(path, BasicFileAttributes::class.java)
                } catch (_: NoSuchFileException) {
                    return add(path, "-")
                }
            val body =
                when {
                    attributes.isDirectory -> "d"
                    !attributes.isRegularFile -> "other " + attributes(attributes)
                    attributes.size() > MAX_HASHED -> "large " + attributes(attributes)
                    else -> hashed(path) ?: return add(path, "-")
                }
            add(path, "f $body ${permissions(path)}")
        }

        /**
         * The SHA-256 of the regular file [path], read at most [MAX_HASHED] + 1 bytes; `null` if it is gone. A file that
         * grew past [MAX_HASHED] since its size was read is being written: no stamp then ([TooMuch]).
         */
        private fun hashed(path: Path): String? {
            val bytes =
                try {
                    Files.newInputStream(path).use { it.readNBytes(MAX_HASHED + 1) }
                } catch (_: NoSuchFileException) {
                    return null
                }
            read += bytes.size
            if (read > maxRead) throw TooMuch()
            if (bytes.size > MAX_HASHED) throw TooMuch()
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        }

        /** The permissions of [path] (POSIX, else `unix:mode` and `unix:ctime`, else whether it is executable). */
        private fun permissions(path: Path): String =
            try {
                PosixFilePermissions.toString(Files.getPosixFilePermissions(path))
            } catch (_: UnsupportedOperationException) {
                try {
                    Files.readAttributes(path, "unix:mode,ctime").toString()
                } catch (_: UnsupportedOperationException) {
                    "x=${Files.isExecutable(path)}"
                } catch (_: IllegalArgumentException) {
                    "x=${Files.isExecutable(path)}"
                }
            }

        /** The entries directly in [folder], each by its [content] and permissions. */
        fun listing(folder: Path) {
            if (!Files.isDirectory(folder)) return content(folder)
            val entries =
                try {
                    Files.list(folder).use { it.sorted().toList() }
                } catch (_: NoSuchFileException) {
                    return add(folder, "-")
                }
            this.entries += entries.size
            if (this.entries > maxEntries) throw TooMuch()
            add(folder, "listing ${entries.size}")
            entries.forEach(::content)
        }

        /**
         * A `.git` by where it leads: a folder as such, a link by its target (and what is there), a `gitdir:` file by
         * its content.
         */
        fun redirect(path: Path) {
            when {
                Files.isSymbolicLink(path) -> {
                    add(path, "link ${Files.readSymbolicLink(path)}")
                    if (Files.isDirectory(path)) add(path, "dir") else content(path)
                }

                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> {
                    add(path, "dir")
                }

                else -> {
                    content(path)
                }
            }
        }

        /** Everything below [folder] except [SKIPPED_FOLDERS], in a stable order; nothing if it does not exist. */
        fun tree(folder: Path) {
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return add(folder, "-")
            val entries = sortedMapOf<String, String>()
            Files.walkFileTree(
                folder,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult =
                        if (dir != folder && dir.fileName.toString() in SKIPPED_FOLDERS) {
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        entries[folder.relativize(file).toString()] = attributes(attrs)
                        return FileVisitResult.CONTINUE
                    }

                    // A ref deleted while it is walked (git rewrites refs all the time) counts as gone.
                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException,
                    ): FileVisitResult {
                        if (exc !is NoSuchFileException) throw exc
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            add(folder, "tree ${entries.size}")
            entries.forEach { (name, text) -> add(folder.resolve(name), text) }
        }
    }
}
