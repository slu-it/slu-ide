package dev.sluide.ide

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.projecttrust.TrustedProjects
import java.nio.file.Path

/** A trust without checks, opened: git may run, as in a folder whose checks find nothing (ADR 0053). */
internal fun trustingTrust(): ProjectTrust =
    ProjectTrust(
        emptyList(),
        TrustedProjects(
            ProjectDataStore(
                Path.of(System.getProperty("java.io.tmpdir"), "sluide-test-trust-never-written"),
                Path.of(System.getProperty("java.io.tmpdir"), "sluide-test-trust-never-written.json"),
                emptyList(),
            ),
        ),
        BackgroundTasks.forTests(),
        Runnable::run,
    ).apply {
        open(Path.of(""))
        awaitAllows("git")
    }
