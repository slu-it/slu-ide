package dev.sluide.ide

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.commandruns.CommandRunSession
import dev.sluide.docker.Docker
import dev.sluide.docker.DockerSession
import dev.sluide.fileviewers.IgnoreRuleEffects
import dev.sluide.fileviewers.WindowServices
import dev.sluide.githubactions.RemoteSources
import dev.sluide.gradle.GradleSession
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.speech.ReadAloud

/**
 * Registers the sessions that file views share within one window:
 * - views of .http files share the window's cookies, tokens and chosen environments (ADR 0026); file views may
 *   register their work as background tasks (ADR 0061); workflow graphs read remote references (ADR 0076);
 *   .gitignore views show which of their lines have an effect (ADR 0080);
 * - views of build files share the task lists of their builds, and Review… of a view whose Gradle is off asks about the
 *   folder's trust (ADR 0085);
 * - views that run commands (shell scripts) run them in the window's terminals and list them among its background
 *   tasks (ADR 0088, ADR 0089);
 * - views of Compose files and Dockerfiles share the window's engine state, lists and events stream (ADR 0117); Compose
 *   views show a service's logs in the Docker tab and open shells in its containers (ADR 0119);
 * - the ebook reader reads aloud through the window's Read Aloud (ADR 0164, ADR 0165).
 */
internal fun WindowServices.registerSessions(
    httpSession: HttpClientSession,
    backgroundTasks: BackgroundTasks,
    workflowSources: RemoteSources,
    ignoreRuleEffects: IgnoreRuleEffects,
    gradleSession: GradleSession,
    commandRuns: CommandRunSession,
    dockerSession: DockerSession,
    docker: Docker,
    readAloud: ReadAloud,
) {
    register(HttpClientSession::class.java, httpSession)
    register(BackgroundTasks::class.java, backgroundTasks)
    register(RemoteSources::class.java, workflowSources)
    register(IgnoreRuleEffects::class.java, ignoreRuleEffects)
    register(GradleSession::class.java, gradleSession)
    register(CommandRunSession::class.java, commandRuns)
    register(DockerSession::class.java, dockerSession)
    register(Docker::class.java, docker)
    register(ReadAloud::class.java, readAloud)
}
