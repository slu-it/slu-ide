package dev.sluide.gitlog.filter

import dev.sluide.git.GitRefKind
import dev.sluide.git.GitTreePath
import dev.sluide.gitlog.data.GitLogPages
import dev.sluide.gitlog.ui.refIcon
import javafx.collections.ListChangeListener
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import java.nio.file.Path

/**
 * What the filter bar (ADR 0039) offers to pick: the refs of the [pages], the authors of the rows loaded since the root
 * was opened, the repository's user name and the paths of `HEAD`'s tree. The user name and the paths are read on
 * [background] when the user or path popup asks for them, and handed back through [dispatch]; the popups are told by
 * [userNameRead] and [pathsRead] to show them.
 */
internal class FilterSources(
    private val pages: GitLogPages,
    private val loadUserName: (Path) -> String?,
    private val loadPaths: (Path) -> List<GitTreePath>?,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
) {
    /** Called on the JavaFX thread when the user name of the opened root has been read. */
    var userNameRead: () -> Unit = {}

    /** Called on the JavaFX thread when the tree paths of the opened root have been read. */
    var pathsRead: () -> Unit = {}

    /** The author names of all rows loaded since the root was opened, for the user filter. */
    private val knownAuthors = HashSet<String>()

    /** The root whose user name is read (or being read); `null` if it is not asked for yet or the read failed. */
    private var userNameRoot: Path? = null

    /** Counts the reads of the user name; only the latest one's result counts. */
    private var userNameReads = 0L
    private var userName: String? = null

    /** The root whose tree paths are read (or being read); `null` if not asked for yet or the read failed. */
    private var pathsRoot: Path? = null

    /** Counts the reads of the tree paths; only the latest one's result counts. */
    private var pathReads = 0L
    private var paths: List<GitTreePath> = emptyList()

    /** Whether the log was refreshed since the paths were read, so that they are read again. */
    private var pathsStale = false

    init {
        pages.rows.addListener(
            ListChangeListener { change ->
                while (change.next()) {
                    if (change.wasAdded()) change.addedSubList.mapTo(knownAuthors) { it.commit.author }
                }
            },
        )
    }

    /** Forgets everything read for the root opened before. */
    fun reset() {
        knownAuthors.clear()
        userNameRoot = null
        userNameReads++
        userName = null
        pathsRoot = null
        pathReads++
        paths = emptyList()
    }

    /** The tree may have changed: the paths are read again when the path popup is shown next. */
    fun markPathsStale() {
        pathsStale = true
    }

    /** `HEAD`, the local branches, remote branches and tags of the refs read last, each kind by name. */
    fun branchItems(): List<FilterItem> {
        val refs = pages.refs?.refs.orEmpty()
        val head = FilterItem("HEAD", "HEAD") { refIcon(GitRefKind.HEAD) }
        return listOf(head) +
            listOf(GitRefKind.LOCAL_BRANCH, GitRefKind.REMOTE_BRANCH, GitRefKind.TAG).flatMap { kind ->
                refs
                    .filter { it.kind == kind }
                    .sortedBy { it.shortName }
                    .map { ref -> FilterItem(ref.name, ref.shortName) { refIcon(kind) } }
            }
    }

    /** "me" if the repository has a user name, then the authors of the loaded rows by name. */
    fun userItems(): List<FilterItem> {
        val me = userName?.let { FilterItem(it, "me ($it)", "me") }
        val authors =
            knownAuthors
                .filter { it != userName }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .map { FilterItem(it, it) }
        return listOfNotNull(me) + authors
    }

    /** The folders and files of `HEAD`'s tree, once read. */
    fun pathItems(): List<FilterItem> = paths.map { pathItem(it.path, it.folder) }

    fun freePath(text: String): FilterItem? =
        normalizeFilterPath(text)?.let { path -> pathItem(path, paths.any { it.folder && it.path == path }) }

    private fun pathItem(
        path: String,
        folder: Boolean,
    ) = FilterItem(path, path) {
        FontIcon(if (folder) MaterialDesignF.FOLDER_OUTLINE else MaterialDesignF.FILE_OUTLINE).apply {
            styleClass += "git-filter-icon"
        }
    }

    /**
     * Reads the user name in the background, once per opened root unless the read failed; the user popup shows it when
     * it is there.
     */
    fun readUserName() {
        val root = pages.root ?: return
        if (userNameRoot == root) return
        userNameRoot = root
        val current = ++userNameReads
        background {
            val name = loadUserName(root)
            dispatch {
                if (pages.root == root && userNameReads == current) {
                    if (name == null) {
                        // A failed read is asked again when the popup is opened next.
                        userNameRoot = null
                    } else {
                        userName = name
                        userNameRead()
                    }
                }
            }
        }
    }

    /**
     * Reads the tree paths in the background, once per opened root and refresh unless the read failed; the path popup
     * shows them then.
     */
    fun readPaths() {
        val root = pages.root ?: return
        if (pathsRoot == root && !pathsStale) return
        pathsRoot = root
        pathsStale = false
        val current = ++pathReads
        background {
            val read = loadPaths(root)
            dispatch {
                if (pages.root == root && pathReads == current) {
                    if (read == null) {
                        // A failed read is asked again when the popup is opened next.
                        pathsRoot = null
                    } else {
                        paths = read
                        pathsRead()
                    }
                }
            }
        }
    }
}
