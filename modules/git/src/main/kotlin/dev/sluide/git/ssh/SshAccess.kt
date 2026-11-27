package dev.sluide.git.ssh

/** How a covered git call may reach ssh's prompts (ADR 0194). */
sealed interface SshAccess {
    /**
     * Today's environment: ssh in batch mode, never asking (ADR 0043); for `RemoteSourceCache`, and when the askpass
     * server is down.
     */
    data object Batch : SshAccess

    /** A session of [SshPrompts]: ssh asks through the askpass helper, and the session's [SshPromptUi] answers. */
    interface Prompting :
        SshAccess,
        AutoCloseable {
        /** Added to git's environment of each call of the session. */
        val environment: Map<String, String>

        /** A git process of this session exited 0: the answers it was given count as good. */
        fun succeeded()

        /** Ends the session; threads waiting on the session's UI are interrupted, their prompts get no answer. */
        override fun close()
    }
}
