package dev.sluide.git

/**
 * Which names git takes for new branches and tags (ADR 0041): the rules of `git check-ref-format`, checked without
 * running git, so that a dialog can tell while the user types.
 */
object GitRefNames {
    /**
     * Whether [name] is a valid branch name, as `git check-ref-format --branch` says: a valid name below `refs/heads/`
     * ([isValidTagName]) that does not start with `-` and is not `HEAD`.
     */
    fun isValidBranchName(name: String): Boolean = name != "HEAD" && !name.startsWith('-') && isValidTagName(name)

    /**
     * Whether [name] is valid below `refs/tags/` (or `refs/heads/`), as `git check-ref-format` says: not empty; no
     * ASCII control characters, space, `~ ^ : ? * [ \`; no `..` and no `@{`; not `@` alone; no component starting with
     * `.` or ending with `.lock`; not starting or ending with `/` and no `//`; not ending with `.`.
     */
    fun isValidTagName(name: String): Boolean {
        if (name.isEmpty() || name == "@") return false
        if (name.any { it.code < 0x20 || it.code == 0x7f || it in FORBIDDEN }) return false
        if (".." in name || "@{" in name) return false
        if (name.endsWith('.')) return false
        val components = name.split('/')
        return components.none { it.isEmpty() || it.startsWith('.') || it.endsWith(".lock") }
    }

    private const val FORBIDDEN = " ~^:?*[\\"
}
