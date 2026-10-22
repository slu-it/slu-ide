package dev.sluide.githubactions.model

import dev.sluide.githubactions.model.UsesReference.DockerImage
import dev.sluide.githubactions.model.UsesReference.LocalAction
import dev.sluide.githubactions.model.UsesReference.LocalWorkflow
import dev.sluide.githubactions.model.UsesReference.RemoteAction
import dev.sluide.githubactions.model.UsesReference.RemoteWorkflow
import dev.sluide.githubactions.model.UsesReference.Unparsed
import dev.sluide.githubactions.model.UsesSite.JOB
import dev.sluide.githubactions.model.UsesSite.STEP
import kotlin.test.Test
import kotlin.test.assertEquals

class UsesReferenceTest {
    @Test
    fun `reads every kind of reference where it may be written`() {
        val sha = "0123456789abcdef0123456789abcdef01234567"
        val cases =
            listOf(
                Triple("actions/checkout@v4", STEP, RemoteAction("actions", "checkout", null, "v4")),
                Triple("gradle/actions/setup-gradle@v6", STEP, RemoteAction("gradle", "actions", "setup-gradle", "v6")),
                Triple("o/r/a/b/c@$sha", STEP, RemoteAction("o", "r", "a/b/c", sha)),
                Triple("o/r@releases/v1", STEP, RemoteAction("o", "r", null, "releases/v1")),
                Triple("./local-action", STEP, LocalAction("./local-action")),
                Triple("./", STEP, LocalAction("./")),
                // Not normalised: resolving must confine paths to their repository.
                Triple("./../x", STEP, LocalAction("./../x")),
                Triple("o/r/../x@v1", STEP, RemoteAction("o", "r", "../x", "v1")),
                Triple("docker://alpine:3", STEP, DockerImage("alpine:3")),
                Triple("docker://ghcr.io/o/i@sha256:abc", STEP, DockerImage("ghcr.io/o/i@sha256:abc")),
                Triple("./.github/workflows/build.yml", JOB, LocalWorkflow("./.github/workflows/build.yml")),
                Triple("./.github/workflows/b.yaml", JOB, LocalWorkflow("./.github/workflows/b.yaml")),
                Triple(
                    "o/r/.github/workflows/deploy.yml@v1",
                    JOB,
                    RemoteWorkflow("o", "r", ".github/workflows/deploy.yml", "v1"),
                ),
                Triple(
                    "o/r/.github/workflows/deploy.yml@$sha",
                    JOB,
                    RemoteWorkflow("o", "r", ".github/workflows/deploy.yml", sha),
                ),
                Triple("  actions/checkout@v4  ", STEP, RemoteAction("actions", "checkout", null, "v4")),
            )
        for ((text, site, expected) in cases) assertEquals(expected, UsesReference.parse(text, site), text)
    }

    @Test
    fun `expressions, garbage and references in the wrong place stay unparsed`() {
        val cases =
            listOf(
                "\${{ matrix.action }}" to STEP,
                "actions/\${{ inputs.name }}@v1" to STEP,
                "actions/checkout" to STEP,
                "actions@v4" to STEP,
                "actions/checkout@" to STEP,
                "/checkout@v4" to STEP,
                "o//r@v1" to STEP,
                "docker://" to STEP,
                "actions/checkout @v4" to STEP,
                "" to STEP,
                "actions/checkout@v4" to JOB,
                "./local-action" to JOB,
                "./.github/workflows/x.yml@v1" to JOB,
                "o/r@v1" to JOB,
                "o/r/.github/workflows/x.yml" to JOB,
                "docker://alpine:3" to JOB,
                // A job calls only workflow files directly in .github/workflows.
                "o/r/path/to/action@v1" to JOB,
                "o/r/workflows/x.yml@v1" to JOB,
                "o/r/.github/x.yml@v1" to JOB,
                "o/r/.github/workflows/sub/x.yml@v1" to JOB,
                "o/r/.github/workflows/../x.yml@v1" to JOB,
                "o/r/.github/workflows/x.json@v1" to JOB,
                "./ci/build.yml" to JOB,
                "./.github/workflows/../../x.yml" to JOB,
            )
        for ((text, site) in cases) assertEquals(Unparsed(text), UsesReference.parse(text, site), "$text at $site")
    }
}
