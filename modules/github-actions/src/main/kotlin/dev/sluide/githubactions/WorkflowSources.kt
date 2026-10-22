package dev.sluide.githubactions

/** Where a workflow's references are read from; a later step adds remote sources. */
interface WorkflowSources {
    /**
     * The text of [path] (relative, `/`-separated, normalised: no `.` or `..`) in the workflow's repository, or null if
     * there is no such file; never a file outside the repository. Throws an exception with a short message when the
     * file cannot be read (outside the repository after following links, too large); [WorkflowGraphs.of] shows it.
     */
    fun local(path: String): String?

    companion object {
        /** No repository: local references stay black boxes, like remote ones. */
        val NONE: WorkflowSources =
            object : WorkflowSources {
                override fun local(path: String): String? = null
            }
    }
}
