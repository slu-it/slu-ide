package dev.sluide.githubactions.model

import dev.sluide.githubactions.sampleJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowJsonTest {
    private fun decode(json: String): Workflow =
        assertIs<WorkflowJson.Result.Decoded>(WorkflowJson.decode(json)).workflow

    @Test
    fun `decodes the parser's output for ci yml`() {
        val workflow = decode(sampleJson("ci"))

        assertEquals("CI", workflow.name)
        assertEquals(emptyList(), workflow.problems)
        assertEquals(listOf(Event("push", "branches: main, master"), Event("pull_request", null)), workflow.events)
        val job = workflow.jobs.single()
        assertEquals("build", job.id)
        assertEquals("Build (\${{ matrix.os }})", job.name)
        assertEquals(16, job.line)
        assertEquals(emptyList(), job.needs)
        assertNull(job.condition)
        assertEquals("\${{ matrix.os }}", job.runsOn)
        assertEquals(mapOf("os" to listOf("ubuntu-latest", "macos-latest")), job.matrix?.axes)
        val steps = assertIs<JobBody.Steps>(job.body).steps
        assertEquals(8, steps.size)
        assertEquals(
            Step(
                null,
                null,
                24,
                StepAction.Uses("actions/checkout@v6", UsesReference.RemoteAction("actions", "checkout", null, "v6")),
                null,
            ),
            steps[0],
        )
        assertEquals(
            Step(
                null,
                "Check docs index",
                30,
                StepAction.Run(".claude/scripts/check-docs-index.sh"),
                "runner.os == 'Linux'",
            ),
            steps[3],
        )
    }

    @Test
    fun `decodes reusable workflow jobs with their inputs and secrets`() {
        val jobs = decode(sampleJson("reusable-and-events")).jobs

        assertEquals(
            JobBody.WorkflowCall(
                "owner/repo/.github/workflows/deploy.yml@v1",
                UsesReference.RemoteWorkflow("owner", "repo", ".github/workflows/deploy.yml", "v1"),
                mapOf("environment" to "production"),
                secretsInherit = true,
            ),
            jobs[1].body,
        )
        assertEquals(listOf("local"), jobs[1].needs)
        val local = assertIs<JobBody.WorkflowCall>(jobs[0].body)
        assertEquals(UsesReference.LocalWorkflow("./.github/workflows/build.yml"), local.reference)
        assertEquals(listOf("java", "flags", "ref"), local.inputs.keys.toList())
    }

    @Test
    fun `decodes problems with and without lines`() {
        val problems = decode(sampleJson("broken-schema")).problems

        assertEquals(7, problems.size)
        assertEquals(Problem("Unexpected value 'colour'", 2, 1), problems[0])
        assertEquals(
            Problem("Could not read jobs: x", null, null),
            decode("""{"schema":1,"errors":[{"message":"Could not read jobs: x","line":null,"column":null}]}""")
                .problems
                .single(),
        )
    }

    @Test
    fun `blank names count as none`() {
        val workflow =
            decode("""{"schema":1,"name":" ","jobs":[{"id":"a","name":"","steps":[{"name":" \t","run":"x"}]}]}""")

        assertNull(workflow.name)
        assertNull(workflow.jobs.single().name)
        assertNull(assertIs<JobBody.Steps>(workflow.jobs.single().body).steps.single().name)
    }

    @Test
    fun `missing fields are empty or null`() {
        val workflow = decode("""{"schema":1,"jobs":[{"id":"a"}]}""")

        assertNull(workflow.name)
        assertEquals(emptyList(), workflow.problems)
        assertEquals(emptyList(), workflow.events)
        assertEquals(
            Job("a", null, null, emptyList(), null, null, null, JobBody.Steps(emptyList())),
            workflow.jobs.single(),
        )
    }

    @Test
    fun `a step has its with inputs in written order, none without one`() {
        val steps =
            assertIs<JobBody.Steps>(
                decode(
                    """
                    {"schema":1,"jobs":[{"id":"a","steps":[
                      {"uses":"actions/checkout@v6","with":{"ref":"${'$'}{{ github.ref }}","fetch-depth":"0","empty":null,"n":1}},
                      {"uses":"actions/cache@v4"},{"uses":"x/y@v1","with":[]}]}]}
                    """,
                ).jobs
                    .single()
                    .body,
            ).steps

        assertEquals(listOf("ref", "fetch-depth", "empty"), steps[0].inputs.keys.toList())
        assertEquals("\${{ github.ref }}", steps[0].inputs["ref"])
        assertEquals("", steps[0].inputs["empty"])
        assertEquals(emptyMap(), steps[1].inputs)
        assertEquals(emptyMap(), steps[2].inputs)
    }

    @Test
    fun `values of the wrong type count as missing`() {
        val workflow =
            decode(
                """
                {"schema":1,"name":5,"errors":{"message":"x"},"events":[{"name":1},{"name":"push","detail":[]}],
                 "jobs":[{"id":7},{"id":"a","name":true,"line":"3","needs":"b","if":{},"runsOn":["x"],
                   "matrix":{"axes":{"os":["a",1,null],"java":"21"},"include":[{"k":2,"v":"w"},"x"],"exclude":{}},
                   "steps":[{"run":1,"uses":2},{"run":"echo","name":3,"if":false},"step"],"surplus":1}]}
                """,
            )

        assertNull(workflow.name)
        assertEquals(emptyList(), workflow.problems)
        assertEquals(listOf(Event("push", null)), workflow.events)
        val job = workflow.jobs.single()
        assertEquals(Job("a", null, null, emptyList(), null, null, job.matrix, job.body), job)
        assertEquals(mapOf("os" to listOf("a", null)), job.matrix?.axes)
        assertEquals(listOf(mapOf("v" to "w")), job.matrix?.include)
        assertEquals(emptyList(), job.matrix?.exclude)
        assertEquals(JobBody.Steps(listOf(Step(null, null, null, StepAction.Run("echo"), null))), job.body)
    }

    @Test
    fun `a job of type job with uses and a reusable job without uses run steps`() {
        val jobs =
            decode(
                """
                {"schema":1,"jobs":[{"id":"a","type":"job","uses":"o/r/.github/workflows/x.yml@v1","steps":[]},
                 {"id":"b","type":"reusableWorkflowJob","uses":null},{"id":"c","uses":"./.github/workflows/c.yml"}]}
                """,
            ).jobs

        assertEquals(JobBody.Steps(emptyList()), jobs[0].body)
        assertEquals(JobBody.Steps(emptyList()), jobs[1].body)
        assertIs<JobBody.WorkflowCall>(jobs[2].body)
    }

    @Test
    fun `other schemas and other text are unsupported`() {
        for ((json, reason) in listOf(
            """{"schema":2,"jobs":[]}""" to "Unsupported parser output (schema 2, expected 1).",
            """{"jobs":[]}""" to "Unsupported parser output (schema missing, expected 1).",
            """{"schema":"1"}""" to "Unsupported parser output (schema missing, expected 1).",
            """{"schema":1""" to "Unsupported parser output (no JSON object).",
            "[1]" to "Unsupported parser output (no JSON object).",
            "" to "Unsupported parser output (no JSON object).",
            "undefined" to "Unsupported parser output (no JSON object).",
        )) {
            val result = WorkflowJson.decode(json)
            assertTrue(result is WorkflowJson.Result.Unsupported && result.reason == reason, "$json gave $result")
        }
    }
}
