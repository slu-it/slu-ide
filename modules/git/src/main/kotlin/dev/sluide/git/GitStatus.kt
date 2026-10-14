package dev.sluide.git

/** What git says about a file or folder, in the order the explorer shows them. Clean files have no status. */
enum class GitStatus {
    /** Not tracked and not ignored. */
    UNVERSIONED,

    /** New in the index (added, or the new path of a rename or copy), or about to be (`git add -N`). */
    ADDED,

    /** Changed, deleted or in conflict, staged or not. */
    MODIFIED,

    /** Excluded by `.gitignore`, `.git/info/exclude` or the global excludes file. */
    IGNORED,
}
