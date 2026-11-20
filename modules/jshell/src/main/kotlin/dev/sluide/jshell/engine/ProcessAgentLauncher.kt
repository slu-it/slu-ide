package dev.sluide.jshell.engine

import dev.sluide.java.Jdk
import dev.sluide.jshell.AgentLauncher
import dev.sluide.jshell.AgentProcess
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The agent's main class (Agent.kt's `@file:JvmName`). */
internal const val AGENT_MAIN = "dev.sluide.jshell.agent.Agent"

/**
 * Runs the agent as `<jdk>/bin/java [jvmOptions] -cp <classPath()> dev.sluide.jshell.agent.Agent`, in Thoreau's
 * working directory and environment.
 */
internal class ProcessAgentLauncher(
    private val classPath: () -> List<Path> = { AgentFiles.install() },
    private val jvmOptions: List<String> = emptyList(),
) : AgentLauncher {
    override fun launch(jdk: Jdk): AgentProcess {
        val java =
            jdk.home
                .resolve("bin")
                .resolve("java")
                .toString()
        val command =
            listOf(java) + jvmOptions + listOf("-cp", classPath().joinToString(File.pathSeparator), AGENT_MAIN)
        return ProcessAgent(ProcessBuilder(command).start())
    }
}

private class ProcessAgent(
    private val process: Process,
) : AgentProcess {
    override val stdin: OutputStream = process.outputStream
    override val stdout: InputStream = process.inputStream
    override val stderr: InputStream = process.errorStream

    override fun waitFor(millis: Long): Boolean = process.waitFor(millis, TimeUnit.MILLISECONDS)

    override fun kill() {
        // the execution JVM first: once the agent is gone, it is no longer among its descendants
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
}
