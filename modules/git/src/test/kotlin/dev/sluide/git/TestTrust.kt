package dev.sluide.git

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.projecttrust.ProjectTrustCheck
import dev.sluide.projecttrust.TrustedProjects
import java.nio.file.Path

/**
 * The trust of an opened [folder] with [checks] (none: every tool may run, as in a folder whose checks find nothing),
 * once they have run; decisions are kept in [store], which nothing writes without a decision.
 */
internal fun testTrust(
    folder: Path = Path.of(""),
    checks: List<ProjectTrustCheck> = emptyList(),
    store: Path = Path.of(System.getProperty("java.io.tmpdir"), "sluide-test-trust-never-written.properties"),
): ProjectTrust =
    ProjectTrust(checks, TrustedProjects(store), BackgroundTasks.forTests(), Runnable::run).apply {
        open(folder)
        awaitAllows(GitTrustCheck.ID)
    }
