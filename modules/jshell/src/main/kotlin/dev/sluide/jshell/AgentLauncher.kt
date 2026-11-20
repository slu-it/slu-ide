package dev.sluide.jshell

import dev.sluide.java.Jdk
import dev.sluide.jshell.engine.ProcessAgentLauncher
import java.io.InputStream
import java.io.OutputStream

/** Starts the JShell agent process (ADR 0191); the seam of the session's tests, whose fake replays recordings. */
fun interface AgentLauncher {
    /**
     * Starts the agent on [jdk]. Blocks (it may copy the agent's files first): worker thread only.
     *
     * @throws java.io.IOException if the process cannot start
     */
    fun launch(jdk: Jdk): AgentProcess

    companion object {
        /** Runs `<jdk>/bin/java -cp <the agent files> dev.sluide.jshell.agent.Agent`, the files from `~/.sluide/jshell`. */
        fun default(): AgentLauncher = ProcessAgentLauncher()
    }
}

/** A started agent: its standard streams and the way to end it. */
interface AgentProcess {
    /** The agent's standard input: requests, one JSON object per line. Closing it ends the agent. */
    val stdin: OutputStream

    /** The agent's standard output: messages, one JSON object per line. */
    val stdout: InputStream

    /** The agent's standard error, read only for its last lines. */
    val stderr: InputStream

    /** Waits up to [millis] for the agent to end; true if it has. */
    fun waitFor(millis: Long): Boolean

    /** Kills the agent and every process it started (the execution JVM). */
    fun kill()
}
